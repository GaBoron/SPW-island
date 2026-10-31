# SPDX-License-Identifier: GPL-3.0-only
"""Install the Arch KWin companion from the bundled source, with GUI authorization and no desktop capture."""
import ctypes as C
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import time
import uuid
import zipfile

NATIVE = ('CMakeLists.txt', 'metadata.json', 'main.cpp', 'IslandGlassEffect.h',
          'IslandGlassEffect.cpp', 'GlassRenderer.h', 'GlassRenderer.cpp')
SHADERS = ('quad.vert', 'contour.frag', 'blur.frag', 'glass.frag')
FILES = set(NATIVE) | {'shaders/' + name for name in SHADERS} | {'LICENSE', 'NOTICE', 'licenses/Apache-2.0.txt'}
PATH = '/io/github/gaboron/SpwIsland/KWinGlass'
INTERFACE = 'io.github.gaboron.SpwIsland.Glass'
PLUGIN_DIRECTORY = Path('/usr/lib/qt6/plugins/kwin/effects/plugins')
PLUGIN = re.compile(r'spw_island_glass_[a-f0-9]{32}')
_session = None


class SetupBus:
    """Reuse the geometry bridge's GIO connection without requiring a gdbus executable."""
    def __init__(self, bridge):
        self.bus = runpy.run_path(bridge)['Bus']()
        def bind(name, result, *arguments):
            function = getattr(self.bus.glib, name)
            function.restype, function.argtypes = result, list(arguments)
            return function
        pointer, string = C.c_void_p, C.c_char_p
        self.parse = bind('g_variant_parse', pointer, pointer, string, pointer, pointer, pointer)
        self.kind = bind('g_variant_get_type_string', string, pointer)
        self.unbox = bind('g_variant_get_variant', pointer, pointer)
        self.boolean = bind('g_variant_get_boolean', C.c_int, pointer)
        self.uint32 = bind('g_variant_get_uint32', C.c_uint, pointer)

    def read(self, variant):
        kind = self.kind(variant)
        if kind in (b's', b'o'):
            return self.bus.get_string(variant, None).decode('utf-8', 'replace')
        if kind == b'b':
            return bool(self.boolean(variant))
        if kind == b'u':
            return self.uint32(variant)
        if kind == b'v':
            child = self.unbox(variant)
            try:
                return self.read(child)
            finally:
                self.bus.unref(child)
        raise RuntimeError('KWin 安装服务返回了不支持的数据类型。')

    def call(self, service, path, interface, method, parameters='()', signature='(s)'):
        error = C.c_void_p()
        params = self.parse(None, parameters.encode(), None, None, C.byref(error))
        if not params:
            self.bus.fail(error, '安装参数无效')
        reply_type = self.bus.type_new(signature.encode())
        try:
            result = self.bus.call_sync(self.bus.connection, service.encode(), path.encode(),
                interface.encode(), method.encode(), params, reply_type, 0, 20000, None, C.byref(error))
        finally:
            # g_variant_parse returns a non-floating reference; call_sync does not consume it.
            self.bus.unref(params)
            self.bus.type_free(reply_type)
        if not result:
            self.bus.fail(error, 'D-Bus ' + method + ' 失败')
        if signature == '()':
            self.bus.unref(result)
            return None
        return self.bus.first(result, self.read)


def session():
    global _session
    if _session is None:
        _session = SetupBus(sys.argv[2])
    return _session


def command(*args, timeout=20):
    result = subprocess.run(args, capture_output=True, text=True, errors='replace',
                            stdin=subprocess.DEVNULL, timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout or 'command failed')[-5000:])
    return result.stdout.strip()


def dbus(path, interface, method, *args, signature='(s)'):
    parameters = '(' + ','.join(json.dumps(arg, ensure_ascii=False) for arg in args) + (',)' if len(args) == 1 else ')')
    return session().call('org.kde.KWin', path, interface, method, parameters, signature)


def version():
    information = dbus('/KWin', 'org.kde.KWin', 'supportInformation')
    match = re.search(r'^KWin version:\s*(6\.(\d+)\.\d+)', information, re.MULTILINE)
    if not match or not 6 <= int(match[2]) <= 8:
        raise RuntimeError('当前 KDE 液态玻璃支持 KWin 6.6–6.8 的 Wayland 会话。')
    backend = dbus('/Compositor', 'org.freedesktop.DBus.Properties', 'Get',
                   'org.kde.kwin.Compositing', 'compositingType', signature='(v)').lower()
    if not any(name in backend for name in ('gl2', 'gles', 'opengl')):
        raise RuntimeError('KWin 液态玻璃需要 OpenGL 合成后端，当前后端使用经典背景。')
    return match[1]


def info():
    try:
        result = json.loads(dbus(PATH, INTERFACE, 'Info'))
        if result.get('protocol') == 1 and PLUGIN.fullmatch(result.get('plugin', '')):
            return result
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired):
        pass
    return None


def content_id(files, names, prefix=''):
    # Immutable product content/SDK IDs, used for module cache and ABI selection.
    digest = hashlib.sha256(prefix.encode())
    for name in names:
        data = files[name]
        digest.update(name.encode() + b'\0' + len(data).to_bytes(8, 'big') + data)
    return digest.hexdigest()[:32]


def bundle(archive):
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        regular = [item for item in entries if not item.is_dir()]
        if ({item.filename for item in regular} != FILES or len(regular) != len(FILES) or
                any((item.external_attr >> 16) & 0o170000 == 0o120000 for item in entries) or
                any(item.file_size > 262144 for item in regular) or
                sum(item.file_size for item in regular) > 2 * 1024 * 1024):
            raise RuntimeError('KWin 配套源代码包内容不正确。')
        files = {name: package.read(name) for name in FILES}
        # Text checkout line endings must not force a privileged native rebuild.
        for name in (*NATIVE, *('shaders/' + item for item in SHADERS)):
            files[name] = files[name].replace(b'\r\n', b'\n')
        return files


def directory(path):
    if not path.is_absolute() or path.is_symlink():
        raise RuntimeError('KWin 支持目录不是普通绝对路径。')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.is_dir():
        raise RuntimeError('KWin 支持目录不可用。')
    return path


def write_immutable(path, data):
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_file() or path.read_bytes() != data:
            raise RuntimeError('已安装的 KWin 修订内容发生变化：' + str(path))
        return
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)


def read_state(root):
    path = root / 'state.json'
    try:
        if path.is_symlink() or path.stat().st_size > 4096:
            return {}
        result = json.loads(path.read_bytes())
        return result if isinstance(result, dict) and result.get('schema') == 1 else {}
    except (OSError, ValueError):
        return {}


@contextmanager
def locked(root):
    descriptor = os.open(root / 'setup.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        deadline = time.monotonic() + 15
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('KWin 支持正在更新，请稍后重试。')
                time.sleep(.1)
        yield
    finally:
        os.close(descriptor)


def load(plugin):
    if not PLUGIN.fullmatch(plugin):
        raise RuntimeError('KWin 原生组件名称无效。')
    # This loads only our companion. No desktop-wide blur/translucency settings are changed.
    reply = dbus('/Effects', 'org.kde.kwin.Effects', 'loadEffect', plugin, signature='(b)')
    if not reply:
        raise RuntimeError('KWin 未能加载匹配的原生组件。')


def build_native(files, root, running, native_revision):
    release = Path('/etc/os-release').read_text()
    if not re.search(r'^ID=\s*"?arch"?\s*$', release, re.MULTILINE):
        raise RuntimeError('当前 KWin 原生组件自动安装支持 Arch Linux。')
    installed = command('pacman', '-Q', 'kwin').split()[1]
    match = re.search(r'(6\.\d+\.\d+)', installed)
    if not match or match[1] != running:
        raise RuntimeError('磁盘与正在运行的 KWin 版本不同，请完成系统更新并重新登录后再安装。')
    compositor_pid = session().call('org.freedesktop.DBus', '/org/freedesktop/DBus',
        'org.freedesktop.DBus', 'GetConnectionUnixProcessID', '("org.kde.KWin",)', '(u)')
    if compositor_pid < 1 or os.readlink(f'/proc/{compositor_pid}/exe').endswith(' (deleted)'):
        raise RuntimeError('正在运行的 KWin 可执行文件已被系统更新替换，请重新登录后再安装原生组件。')
    tools = ['cmake', 'gcc', 'make', 'pkgconf', 'extra-cmake-modules', 'vulkan-headers']
    missing = [name for name in tools if subprocess.run(
        ['pacman', '-Q', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode]
    if missing:
        # Explicit setup may request the desktop's PolicyKit authentication dialog; automatic refresh never does.
        # Never terminate a package-manager transaction on a launcher timeout.
        command('pkexec', '/usr/bin/pacman', '-S', '--needed', '--noconfirm', *missing, timeout=None)
    job = directory(root / 'builds' / uuid.uuid4().hex)
    source = directory(job / 'source')
    for name in NATIVE:
        write_immutable(source / name, files[name])
    plugin = 'spw_island_glass_' + uuid.uuid4().hex
    output = job / 'build'
    command('cmake', '-S', str(source), '-B', str(output), '-DCMAKE_BUILD_TYPE=Release',
            '-DSPW_PLUGIN_NAME=' + plugin, '-DSPW_NATIVE_REVISION=' + native_revision,
            '-DSPW_EXPECTED_KWIN_VERSION=' + running, timeout=60)
    command('cmake', '--build', str(output), '--parallel', '2', timeout=180)
    library = output / (plugin + '.so')
    with library.open('rb') as stream:
        header = stream.read(20)
    if header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 62:
        raise RuntimeError('KWin 原生组件不是 Linux x64 动态库。')
    destination = PLUGIN_DIRECTORY / library.name
    if destination.exists() or destination.is_symlink():
        raise RuntimeError('拒绝覆盖已有的 KWin 组件。')
    command('pkexec', '/usr/bin/install', '-D', '-m', '0755', '--', str(library), str(destination), timeout=60)
    return plugin


def refresh(archive, root, automatic):
    files = bundle(archive)
    if automatic and not read_state(root):
        return {'installed': False, 'ready': False, 'changed': False, 'message': ''}
    running = version()
    native_revision = content_id(files, NATIVE, running)
    revision = content_id(files, ['shaders/' + name for name in SHADERS])
    with locked(root):
        previous = read_state(root)
        if automatic and not previous:
            return {'installed': False, 'ready': False, 'changed': False, 'message': ''}
        current = info()
        native_ok = (previous.get('native') == native_revision and previous.get('kwin') == running and
                     PLUGIN.fullmatch(previous.get('plugin', '')))
        if not automatic and native_ok and not (PLUGIN_DIRECTORY / (previous['plugin'] + '.so')).is_file():
            native_ok = False
        if automatic and not native_ok:
            return {'installed': True, 'ready': False, 'changed': False, 'needsRepair': True,
                    'message': 'KWin 原生接口已更新，请在原有“安装 / 修复 Linux 液态玻璃支持”入口更新配套组件。'}
        revisions = directory(root / 'revisions')
        shaders = directory(revisions / revision)
        for name in SHADERS:
            write_immutable(shaders / name, files['shaders/' + name])
        plugin = previous.get('plugin') if native_ok else None
        built = False
        if not plugin:
            plugin = build_native(files, root, running, native_revision)
            built = True
        old_plugin = current.get('plugin') if current else None
        try:
            if current and (current.get('native') != native_revision or current.get('kwin') != running):
                dbus('/Effects', 'org.kde.kwin.Effects', 'unloadEffect', old_plugin, signature='()')
                current = None
            if not current:
                try:
                    load(plugin)
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    if automatic:
                        return {'installed': True, 'ready': False, 'changed': False, 'needsRepair': True,
                                'message': 'KWin 配套原生组件无法加载，请从原有安装 / 修复入口重新部署。'}
                    if built:
                        raise
                    plugin = build_native(files, root, running, native_revision)
                    load(plugin)
                current = info()
            if not current or current.get('native') != native_revision or current.get('kwin') != running:
                raise RuntimeError('已加载的 KWin 组件与当前接口不匹配。')
            result = dbus(PATH, INTERFACE, 'Reload', revision)
            if result != 'LOADED':
                raise RuntimeError(str(result))
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired):
            if old_plugin and old_plugin != plugin and not info():
                try:
                    load(old_plugin)
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    pass
            raise
        state = {'schema': 1, 'plugin': current['plugin'], 'native': native_revision,
                 'kwin': running, 'revision': revision}
        if previous.get('revision') != revision:
            state['previous'] = previous.get('revision')
        else:
            state['previous'] = previous.get('previous')
        if previous == state:
            return {'installed': True, 'ready': True, 'changed': False, 'message': 'KWin 液态玻璃支持已就绪。'}
        pending = root / ('state-' + uuid.uuid4().hex + '.json')
        write_immutable(pending, json.dumps(state, sort_keys=True).encode())
        # Keep the prior manifest; updates never permanently delete or overwrite old support data.
        manifest = root / 'state.json'
        if manifest.exists() or manifest.is_symlink():
            if manifest.is_symlink():
                raise RuntimeError('KWin 状态文件不是普通文件。')
            manifest.rename(root / ('state-backup-' + uuid.uuid4().hex + '.json'))
        pending.rename(manifest)
        return {'installed': True, 'ready': True, 'changed': previous != state,
                'message': 'KWin 液态玻璃模块已加载，无需注销；后续着色器更新随插件自动同步。'}


def host_setup(archive, root):
    """The user service executes against host KWin headers even when SPW uses Steam Runtime."""
    job = directory(root / 'jobs' / uuid.uuid4().hex)
    helper = job / 'setup.py'
    write_immutable(helper, Path(__file__).read_bytes())
    bridge = job / 'island-pointer.py'
    write_immutable(bridge, Path(sys.argv[2]).read_bytes())
    diagnostics = job / 'island-diagnostics.py'
    write_immutable(diagnostics, Path(os.environ['SPW_ISLAND_PY_DIAGNOSTICS']).read_bytes())
    shared_archive = job / 'companion.zip'
    write_immutable(shared_archive, Path(archive).read_bytes())
    output, error = job / 'result.json', job / 'error.log'
    q = lambda text: json.dumps(str(text), ensure_ascii=False)
    arguments = ['/usr/bin/python3', str(helper), str(shared_archive), str(bridge), '--host']
    environment = ['XDG_DATA_HOME=' + str(root.parent.parent), 'SPW_ISLAND_PY_DIAGNOSTICS=' + str(diagnostics)]
    for key in ('XDG_CURRENT_DESKTOP', 'XDG_SESSION_TYPE', 'DISPLAY', 'WAYLAND_DISPLAY', 'DBUS_SESSION_BUS_ADDRESS'):
        if key in os.environ:
            environment.append(key + '=' + os.environ[key])
    properties = '[' + ','.join([
        '("Description", <"SPW Island KWin companion setup">)',
        '("Type", <"exec">)',
        '("ExecStart", <@a(sasb) [(' + q(arguments[0]) + ', @as [' + ','.join(map(q, arguments)) + '], false)]>)',
        '("Environment", <@as [' + ','.join(map(q, environment)) + ']>)',
        '("StandardOutput", <' + q('file:' + str(output)) + '>)',
        '("StandardError", <' + q('file:' + str(error)) + '>)',
        '("CollectMode", <"inactive-or-failed">)',
    ]) + ']'
    unit = 'spw-island-kwin-setup-' + uuid.uuid4().hex + '.service'
    session().call('org.freedesktop.systemd1', '/org/freedesktop/systemd1',
        'org.freedesktop.systemd1.Manager', 'StartTransientUnit',
        '(' + q(unit) + ', "fail", ' + properties + ', @a(sa(sv)) [])', '(o)')
    deadline = time.monotonic() + 605
    while time.monotonic() < deadline:
        try:
            if not output.is_symlink() and output.stat().st_size <= 65536:
                result = json.loads(output.read_bytes())
                if 'error' in result:
                    raise RuntimeError(result['error'])
                return result
        except (OSError, ValueError):
            pass
        time.sleep(.25)
    raise RuntimeError('KWin 安装尚未完成，请检查系统授权窗口。已授权的系统安装任务会继续完成，请稍后重试。')


if __name__ == '__main__':
    try:
        automatic = '--refresh-existing' in sys.argv[3:]
        if 'kde' not in os.environ.get('XDG_CURRENT_DESKTOP', '').lower().split(':'):
            raise RuntimeError('此组件用于 KDE Plasma 桌面。')
        if os.environ.get('XDG_SESSION_TYPE') != 'wayland':
            raise RuntimeError('当前 KWin 液态玻璃接入使用 Plasma 6 的 Wayland 会话。')
        data_home = Path(os.environ.get('XDG_DATA_HOME') or str(Path.home() / '.local/share'))
        root = directory(directory(data_home / 'spw-island-support') / 'kwin-glass')
        # Validate before allowing a host-side build or requesting any system authorization.
        bundle(sys.argv[1])
        result = refresh(sys.argv[1], root, automatic) if automatic or '--host' in sys.argv[3:] else host_setup(sys.argv[1], root)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except Exception as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), flush=True)
        sys.exit(1)
