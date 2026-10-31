# SPDX-License-Identifier: GPL-3.0-only
"""Install bundled GNOME companions on demand, preserving unrelated Shell settings."""
import ast
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import tempfile
import zipfile
import runpy

diagnostics = runpy.run_path(os.environ.get(
    "SPW_ISLAND_PY_DIAGNOSTICS", os.path.join(os.path.dirname(__file__), "island-diagnostics.py")))["install"]("install-gnome-support.py")

SUPPORTS = {
    'pointer': {
        'uuid': 'spw-island-pointer@gaboron.github.io',
        'label': 'GNOME 悬停隐藏支持',
        'versions': range(45, 51),
        'files': {'extension.js', 'metadata.json', 'LICENSE'},
    },
    'glass': {
        'uuid': 'spw-island-glass@gaboron.github.io',
        'label': 'GNOME 液态玻璃支持',
        'versions': (50,),
        'files': {'extension.js', 'surface.js', 'renderer.js', 'metadata.json', 'contour.glsl', 'blur.glsl',
                  'glass.glsl', 'LICENSE', 'NOTICE', 'licenses/Apache-2.0.txt'},
    },
}

RENDERER_FILES = ('renderer.js', 'contour.glsl', 'blur.glsl', 'glass.glsl')


def renderer_revision(files):
    # Product content IDs give ESM imports immutable URLs; they are not public plugin versions.
    digest = hashlib.sha256()
    for name in RENDERER_FILES:
        content = files[name]
        digest.update(name.encode() + b'\0' + len(content).to_bytes(8, 'big') + content)
    return digest.hexdigest()[:32]


def renderer_layout(files, directory):
    revision = renderer_revision(files)
    deployed = {name: content for name, content in files.items() if name not in RENDERER_FILES}
    for name in RENDERER_FILES:
        deployed[f'revisions/{revision}/{name}'] = files[name]
    manifest = {'schema': 1, 'revision': revision}
    try:
        pointer = directory / 'backend.json'
        if pointer.is_symlink() or pointer.stat().st_size > 4096:
            raise ValueError('invalid renderer pointer')
        old = json.loads(pointer.read_bytes())
        if not isinstance(old, dict):
            raise ValueError('invalid renderer pointer')
        previous = old.get('previous') if old.get('revision') == revision else old.get('revision')
        if old.get('schema') != 1 or not isinstance(previous, str) or not re.fullmatch('[a-f0-9]{32}', previous):
            raise ValueError('no previous renderer')
        base = directory / 'revisions' / previous
        if (directory / 'revisions').is_symlink() or base.is_symlink():
            raise ValueError('invalid previous renderer directory')
        saved = {}
        for name in RENDERER_FILES:
            path = base / name
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 262144:
                raise ValueError('invalid previous renderer file')
            saved[name] = path.read_bytes()
        if renderer_revision(saved) != previous:
            raise ValueError('previous renderer content changed')
        manifest['previous'] = previous
        for name, content in saved.items():
            deployed[f'revisions/{previous}/{name}'] = content
    except (OSError, ValueError):
        pass  # A legacy install or absent fallback does not prevent installing the current bundle.
    deployed['backend.json'] = json.dumps(manifest, sort_keys=True).encode()
    return deployed, revision


@contextmanager
def setup_lock(data_home):
    root = data_home / 'spw-island-support'
    if root.is_symlink():
        raise RuntimeError('GNOME 支持锁目录不是普通目录')
    root.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(root / 'setup.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise RuntimeError('GNOME 支持锁文件类型不正确')
        deadline = time.monotonic() + 10
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('GNOME 支持正在由另一进程更新，请稍后重试。')
                time.sleep(.05)
        yield
    finally:
        os.close(descriptor)


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or '扩展安装失败')
    return result.stdout.strip()


def extensions(key):
    values = ast.literal_eval(command('gsettings', 'get', 'org.gnome.shell', key).removeprefix('@as '))
    if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
        raise RuntimeError('无法读取 GNOME 扩展设置')
    return values


def package_files(archive, support, version):
    """Only known regular files from the bundled companion can reach the install directory."""
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        names = [item.filename for item in entries]
        allowed = support['files']
        directories = {str(parent) + '/' for name in allowed for parent in Path(name).parents if str(parent) != '.'}
        if (len(names) != len(set(names)) or
                {item.filename for item in entries if not item.is_dir()} != allowed or
                any(item.filename not in directories for item in entries if item.is_dir()) or
                any((item.external_attr >> 16) & 0o170000 == 0o120000 for item in entries) or
                sum(item.file_size for item in entries) > 4 * 1024 * 1024):
            raise RuntimeError('GNOME 配套扩展包内容不正确')
        files = {name: package.read(name) for name in allowed}
        metadata = json.loads(files['metadata.json'])
        if metadata.get('uuid') != support['uuid'] or str(version) not in metadata.get('shell-version', []):
            raise RuntimeError('GNOME 配套扩展标识或版本不匹配')
        return files


def recycle_staging(staging, root):
    if not staging.exists():
        return
    if staging.parent.resolve() != root or not staging.name.startswith('.spw-island-') or staging.is_symlink():
        raise RuntimeError('拒绝清理不属于本次安装的路径：' + str(staging))
    try:
        command('gio', 'trash', '--', str(staging))
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        # A filesystem without Trash support must retain the staging directory.
        diagnostics.failure('extension.staging_trash_failed', error)
        print('安装暂存目录保留在 ' + str(staging) + '；无法移入回收站：' + str(error), file=sys.stderr)


def deploy(directory, root, backup_root, files):
    if directory.is_symlink() or directory.exists() and not directory.is_dir():
        raise RuntimeError('配套扩展安装位置不是普通目录：' + str(directory))
    if directory.is_dir() and all(
            not (directory / name).is_symlink() and (directory / name).is_file() and
            (directory / name).read_bytes() == content for name, content in files.items()):
        return False
    # Same-filesystem rename keeps replacement atomic; old versions remain recoverable.
    staging = Path(tempfile.mkdtemp(prefix='.spw-island-', dir=root))
    backup = None
    try:
        for name, content in files.items():
            destination = staging / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
        if directory.exists():
            backup = backup_root / directory.name / str(time.time_ns())
            backup.parent.mkdir(parents=True, exist_ok=True)
            directory.rename(backup)
        try:
            staging.rename(directory)
        except OSError as error:
            diagnostics.failure('extension.replace_failed', error)
            if backup is not None:
                backup.rename(directory)
            raise
    finally:
        recycle_staging(staging, root)
    return True


def enable(uuid):
    # The install action opens the global gate; individual entries still change only for this companion.
    enabled, disabled = extensions('enabled-extensions'), extensions('disabled-extensions')
    if uuid not in enabled:
        command('gsettings', 'set', 'org.gnome.shell', 'enabled-extensions', repr(enabled + [uuid]))
    if uuid in disabled:
        command('gsettings', 'set', 'org.gnome.shell', 'disabled-extensions', repr([item for item in disabled if item != uuid]))
    if command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') == 'true':
        command('gsettings', 'set', 'org.gnome.shell', 'disable-user-extensions', 'false')
        if command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') != 'false':
            raise RuntimeError('GNOME 用户扩展总开关未能开启，请重试或检查系统策略。')
    try:
        # Existing companions can be re-enabled immediately. New ones are discovered at login.
        if uuid in command('gnome-extensions', 'list').splitlines():
            command('gnome-extensions', 'enable', uuid)
            return True
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        diagnostics.failure('extension.enable_after_login', error)
    return False


def reload_glass(revision):
    try:
        interface = command('gdbus', 'introspect', '--session', '--dest', 'org.gnome.Shell',
                            '--object-path', '/io/github/gaboron/SpwIsland/Glass')
        if 'UpdateManaged' not in interface or 'Reload' not in interface:
            return False
        response = command('gdbus', 'call', '--session', '--dest', 'org.gnome.Shell',
                           '--object-path', '/io/github/gaboron/SpwIsland/Glass',
                           '--method', 'io.github.gaboron.SpwIsland.Glass.Reload', revision)
        values = ast.literal_eval(response)
        if not isinstance(values, tuple) or not values or values[0] != 'LOADED':
            raise RuntimeError(str(values[0]) if values else '渲染模块未加载')
        return True
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        diagnostics.failure('extension.glass_reload_pending', error)
        return False


def install(kind, archive, refresh_existing=False):
    support = SUPPORTS[kind]
    if refresh_existing and kind != 'glass':
        raise RuntimeError('自动同步仅用于液态玻璃支持')
    desktop = os.environ.get('XDG_CURRENT_DESKTOP', '').lower().split(':')
    if 'gnome' not in desktop:
        raise RuntimeError('此配套扩展仅用于 GNOME 桌面')
    match = re.search(r'(\d+)', command('gnome-shell', '--version'))
    if match is None or int(match.group(1)) not in support['versions']:
        supported = '50' if kind == 'glass' else '45–50'
        raise RuntimeError(support['label'] + '适用于 GNOME ' + supported + '，当前桌面版本不支持。')
    files = package_files(archive, support, int(match.group(1)))
    data_home = Path(os.environ.get('XDG_DATA_HOME') or str(Path.home() / '.local/share'))
    if not data_home.is_absolute():
        raise RuntimeError('GNOME 用户数据目录必须是绝对路径')
    data_home = data_home.resolve()
    root = (data_home / 'gnome-shell/extensions').resolve()
    directory = root / support['uuid']
    if refresh_existing and not directory.is_dir():
        return {'installed': False, 'ready': False, 'changed': False, 'message': ''}
    if not refresh_existing:
        for key in ('enabled-extensions', 'disabled-extensions'):
            if command('gsettings', 'writable', 'org.gnome.shell', key) != 'true':
                raise RuntimeError('系统策略禁止修改 GNOME 扩展设置，请联系系统管理员。')
            extensions(key)
        if (command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') == 'true' and
                command('gsettings', 'writable', 'org.gnome.shell', 'disable-user-extensions') != 'true'):
            raise RuntimeError('系统策略禁止开启 GNOME 用户扩展，请联系系统管理员。')
    root.mkdir(parents=True, exist_ok=True)
    with setup_lock(data_home):
        revision = None
        if kind == 'glass':
            files, revision = renderer_layout(files, directory)
        changed = deploy(directory, root, data_home / 'spw-island-backups', files)
        try:
            if refresh_existing:
                active = (command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') == 'false' and
                          support['uuid'] in extensions('enabled-extensions') and
                          support['uuid'] not in extensions('disabled-extensions'))
            else:
                active = enable(support['uuid'])
            configured = active
            if kind == 'glass':
                active = active and reload_glass(revision)
            diagnostics.emit('extension.setup_result', {'kind': kind, 'ready': active,
                             'filesChanged': changed, 'automatic': refresh_existing})
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            raise RuntimeError(support['label'] + '已安装，但自动启用失败。请重试安装 / 修复。\n' + str(error)) from error
    if kind == 'glass':
        if refresh_existing and not configured:
            message = ''  # Explicitly disabled extensions stay disabled, without prompting to change settings.
        elif active:
            message = 'GNOME 液态玻璃支持已就绪。'
        else:
            message = 'GNOME 液态玻璃支持已安装，请保存工作并重新登录。'
    elif changed or not active:
        message = support['label'] + '已安装，用户扩展总开关已开启。请保存工作，注销后重新登录。'
    else:
        message = support['label'] + '已就绪。'
    return {'installed': True, 'ready': active, 'changed': changed, 'message': message}


if __name__ == '__main__':
    automatic = '--refresh-existing' in sys.argv[3:]
    try:
        result = install(sys.argv[1], sys.argv[2], automatic)
        print(json.dumps(result, ensure_ascii=False) if automatic else result['message'])
    except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        diagnostics.failure('caught.error', error)
        print(json.dumps({'error': str(error)}, ensure_ascii=False) if automatic else str(error))
        sys.exit(1)
