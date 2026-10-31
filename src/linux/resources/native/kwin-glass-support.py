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
import stat
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
ENTRY = 'spw_island_glass_entry_' + str(os.getuid())
_session = None


class SetupError(RuntimeError):
    """Keep actionable UI text separate from bounded command details for diagnostics."""
    def __init__(self, message, detail=''):
        super().__init__(message)
        self.detail = detail[-5000:]


def connect_bus(bridge, host):
    factory = runpy.run_path(bridge)['Bus']
    try:
        return factory()
    except (OSError, RuntimeError) as first_error:
        failure = first_error
        if host:
            # A stale manager address may be recovered only through this UID's existing session socket.
            uid = os.getuid()
            runtime = Path('/run/user') / str(uid)
            endpoint = runtime / 'bus'
            try:
                runtime_info, endpoint_info = runtime.stat(), endpoint.stat()
                if (runtime_info.st_uid == uid and stat.S_ISDIR(runtime_info.st_mode) and
                        endpoint_info.st_uid == uid and stat.S_ISSOCK(endpoint_info.st_mode)):
                    os.environ['XDG_RUNTIME_DIR'] = str(runtime)
                    os.environ['DBUS_SESSION_BUS_ADDRESS'] = 'unix:path=' + str(endpoint)
                    return factory()
            except (OSError, RuntimeError) as retry_error:
                failure = retry_error
        phase = '宿主安装任务' if host else '插件环境'
        raise RuntimeError(phase + '无法连接桌面会话 D-Bus：' + str(failure)) from failure


class SetupBus:
    """Reuse the geometry bridge's GIO connection without requiring a gdbus executable."""
    def __init__(self, bridge, host):
        self.bus = connect_bus(bridge, host)
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
        _session = SetupBus(sys.argv[2], '--host' in sys.argv[3:])
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


def plugin_directory(root):
    return directory(root / 'modules')


def discovery_installed(root):
    link = PLUGIN_DIRECTORY / (ENTRY + '.so')
    if not link.exists() and not link.is_symlink():
        return False
    if not link.is_symlink() or link.lstat().st_uid != 0 or os.readlink(link) != str(root / 'entry.so'):
        raise RuntimeError('KWin 固定入口已被其他文件占用，未覆盖：' + str(link))
    return True


def register_discovery(root):
    if not discovery_installed(root):
        # Qt resolves this link to the immutable library's canonical filename;
        # KPluginMetaData derives its ID from that name, not the fixed entry name.
        command('pkexec', '/usr/bin/ln', '-s', '--', str(root / 'entry.so'),
                str(PLUGIN_DIRECTORY / (ENTRY + '.so')), timeout=60)
        if not discovery_installed(root):
            raise RuntimeError('KWin 固定入口注册未完成。')


def select_entry(root, library):
    plugins = plugin_directory(root)
    if library.parent != plugins or not PLUGIN.fullmatch(library.stem) or library.is_symlink() or not library.is_file():
        raise RuntimeError('KWin 用户组件路径无效。')
    entry = root / 'entry.so'
    previous = None
    if entry.is_symlink():
        previous = Path(os.readlink(entry))
        if previous.parent != plugins or not PLUGIN.fullmatch(previous.stem):
            raise RuntimeError('KWin 用户入口指向了非托管组件。')
    elif entry.exists():
        raise RuntimeError('KWin 用户入口不是符号链接。')
    if previous == library:
        return previous
    pending = root / ('entry-' + uuid.uuid4().hex + '.so')
    pending.symlink_to(library)
    backup = root / ('entry-backup-' + uuid.uuid4().hex + '.so')
    if previous is not None:
        entry.rename(backup)
    try:
        pending.rename(entry)
    except OSError:
        if previous is not None:
            backup.rename(entry)
        raise
    return previous


def verify_native_compatibility(running):
    installed = command('pacman', '-Q', 'kwin').split()[1]
    match = re.search(r'(6\.\d+\.\d+)', installed)
    if not match or match[1] != running or version() != running:
        raise RuntimeError('磁盘与正在运行的 KWin 版本不同，请完成系统更新并重新登录后再安装。')
    compositor_pid = session().call('org.freedesktop.DBus', '/org/freedesktop/DBus',
        'org.freedesktop.DBus', 'GetConnectionUnixProcessID', '("org.kde.KWin",)', '(u)')
    if compositor_pid < 1:
        raise RuntimeError('无法确认当前 KWin 进程，请稍后重试安装。')
    try:
        executable = os.readlink(f'/proc/{compositor_pid}/exe')
    except PermissionError:
        # Compositors may deny ptrace-style /proc access; version and SDK matching remain mandatory.
        executable = None
    except FileNotFoundError as error:
        raise RuntimeError('KWin 进程已发生变化，请稍后重试安装。') from error
    if executable is not None and executable.endswith(' (deleted)'):
        raise RuntimeError('正在运行的 KWin 可执行文件已被系统更新替换，请重新登录后再安装原生组件。')
    return installed, compositor_pid


def build_native(files, root, running, native_revision, automatic):
    release = Path('/etc/os-release').read_text()
    if not re.search(r'^ID=\s*"?arch"?\s*$', release, re.MULTILINE):
        raise RuntimeError('当前 KWin 原生组件自动安装支持 Arch Linux。')
    baseline = verify_native_compatibility(running)
    tools = ['cmake', 'gcc', 'make', 'pkgconf', 'extra-cmake-modules', 'vulkan-headers']
    missing = [name for name in tools if subprocess.run(
        ['pacman', '-Q', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode]
    if missing:
        if automatic:
            raise RuntimeError('KWin 自动更新缺少编译依赖：' + '、'.join(missing) +
                               '。请从原有安装 / 修复入口补齐依赖；自动更新不会请求系统授权。')
        # Explicit setup may request the desktop's PolicyKit authentication dialog; automatic refresh never does.
        # Never terminate a package-manager transaction on a launcher timeout.
        try:
            command('pkexec', '/usr/bin/pacman', '-S', '--needed', '--noconfirm', *missing, timeout=None)
        except RuntimeError as error:
            detail = str(error)
            if 'returned error: 404' in detail and '.pkg.tar.' in detail:
                raise SetupError('Arch 编译工具下载失败（镜像返回 404）。请先手动完整更新系统，重新登录后再从此入口安装。',
                                 detail) from error
            raise
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
    destination = plugin_directory(root) / library.name
    write_immutable(destination, library.read_bytes())
    if verify_native_compatibility(running) != baseline:
        raise RuntimeError('KWin 软件包或进程在安装期间发生变化，原生组件尚未加载，请重试安装。')
    return plugin


def refresh(archive, root, automatic):
    files = bundle(archive)
    if automatic and not read_state(root):
        return {'installed': False, 'ready': False, 'changed': False, 'message': ''}
    running = version()
    revision = content_id(files, ['shaders/' + name for name in SHADERS])
    with locked(root):
        previous = read_state(root)
        if automatic and not previous:
            return {'installed': False, 'ready': False, 'changed': False, 'message': ''}
        managed = discovery_installed(root)
        if automatic and not managed:
            return {'installed': True, 'ready': False, 'changed': False, 'needsRepair': True,
                    'message': '请在插件设置中安装 / 修复 Linux 液态玻璃支持。'}
        baseline = verify_native_compatibility(running)
        native_revision = content_id(files, NATIVE, running + '\0' + baseline[0])
        current = info()
        native_ok = (previous.get('native') == native_revision and previous.get('kwin') == running and
                     previous.get('managed') is True and PLUGIN.fullmatch(previous.get('plugin', '')))
        plugins = plugin_directory(root)
        if native_ok:
            library = plugins / (previous['plugin'] + '.so')
            native_ok = library.is_file() and not library.is_symlink()
        revisions = directory(root / 'revisions')
        shaders = directory(revisions / revision)
        for name in SHADERS:
            write_immutable(shaders / name, files['shaders/' + name])
        plugin = previous.get('plugin') if native_ok else None
        if not plugin:
            plugin = build_native(files, root, running, native_revision, automatic)
        library = plugins / (plugin + '.so')
        if verify_native_compatibility(running) != baseline:
            raise RuntimeError('KWin 软件包或进程在更新期间发生变化，请重新启用插件。')
        old_plugin = current.get('plugin') if current else None
        old_current = current
        old_entry = select_entry(root, library)
        new_loaded = None
        try:
            if not automatic:
                register_discovery(root)
            if current and (current.get('native') != native_revision or current.get('kwin') != running or
                            current.get('plugin') != plugin):
                dbus('/Effects', 'org.kde.kwin.Effects', 'unloadEffect', old_plugin, signature='()')
                current = None
            if not current:
                load(plugin)
                new_loaded = plugin
                current = info()
            if (not current or current.get('native') != native_revision or current.get('kwin') != running or
                    current.get('plugin') != plugin):
                raise RuntimeError('已加载的 KWin 组件与当前接口不匹配。')
            result = dbus(PATH, INTERFACE, 'Reload', revision)
            if result != 'LOADED':
                raise RuntimeError(str(result))
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired):
            if new_loaded:
                try:
                    dbus('/Effects', 'org.kde.kwin.Effects', 'unloadEffect', new_loaded, signature='()')
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    pass
            if old_entry is not None and old_entry.is_file():
                select_entry(root, old_entry)
            if old_plugin and not info():
                try:
                    load(old_plugin)
                    old_revision = old_current.get('revision') or previous.get('revision', '')
                    if re.fullmatch('[a-f0-9]{32}', old_revision):
                        dbus(PATH, INTERFACE, 'Reload', old_revision)
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    pass
            raise
        state = {'schema': 1, 'managed': True, 'plugin': current['plugin'], 'native': native_revision,
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
                'message': 'KWin 液态玻璃支持已就绪。'}


def host_setup(archive, root, automatic):
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
    if automatic:
        arguments.append('--refresh-existing')
    environment = ['XDG_DATA_HOME=' + str(root.parent.parent), 'SPW_ISLAND_PY_DIAGNOSTICS=' + str(diagnostics)]
    # The plugin may use a container-only proxy. Host tasks inherit their user manager's native bus address.
    for key in ('XDG_CURRENT_DESKTOP', 'XDG_SESSION_TYPE', 'DISPLAY', 'WAYLAND_DISPLAY'):
        if key in os.environ:
            environment.append(key + '=' + os.environ[key])
    properties = '[' + ','.join([
        '("Description", <"SPW Island KWin companion setup">)',
        '("Type", <"exec">)',
        '("ExecStart", <@a(sasb) [(' + q(arguments[0]) + ', @as [' + ','.join(map(q, arguments)) + '], false)]>)',
        '("Environment", <@as [' + ','.join(map(q, environment)) + ']>)',
        # D-Bus file properties take paths; the "file:" prefix belongs to unit-file syntax.
        '("StandardOutputFile", <' + q(output) + '>)',
        '("StandardErrorFile", <' + q(error) + '>)',
        '("CollectMode", <"inactive-or-failed">)',
    ]) + ']'
    unit = 'spw-island-kwin-setup-' + uuid.uuid4().hex + '.service'
    session().call('org.freedesktop.systemd1', '/org/freedesktop/systemd1',
        'org.freedesktop.systemd1.Manager', 'StartTransientUnit',
        '(' + q(unit) + ', "fail", ' + properties + ', @a(sa(sv)) [])', '(o)')
    deadline = time.monotonic() + (305 if automatic else 605)
    while time.monotonic() < deadline:
        try:
            if not output.is_symlink() and output.stat().st_size <= 65536:
                result = json.loads(output.read_bytes())
                if 'error' in result:
                    raise SetupError(result['error'], result.get('detail', ''))
                return result
        except (OSError, ValueError):
            pass
        time.sleep(.25)
    if automatic:
        raise RuntimeError('KWin 自动更新尚未完成，后台任务仍会继续；请稍后重新启用插件。')
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
        if '--host' in sys.argv[3:]:
            result = refresh(sys.argv[1], root, automatic)
        elif automatic and not (previous := read_state(root)).get('managed'):
            # Legacy installs need one explicit migration; never open PolicyKit at startup.
            result = {'installed': bool(previous), 'ready': False, 'changed': False,
                      'needsRepair': bool(previous),
                      'message': '请在插件设置中安装 / 修复 Linux 液态玻璃支持。'
                                 if previous else ''}
        else:
            result = host_setup(sys.argv[1], root, automatic)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except Exception as error:
        result = {'error': str(error)}
        if isinstance(error, SetupError) and error.detail:
            result['detail'] = error.detail
        print(json.dumps(result, ensure_ascii=False), flush=True)
        sys.exit(1)
