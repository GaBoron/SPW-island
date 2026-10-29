# SPDX-License-Identifier: GPL-3.0-only
"""Install bundled GNOME companions on demand, preserving unrelated Shell settings."""
import ast
import json
import os
from pathlib import Path
import re
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
        'files': {'extension.js', 'renderer.js', 'metadata.json', 'contour.glsl', 'blur.glsl',
                  'glass.glsl', 'LICENSE', 'NOTICE', 'licenses/Apache-2.0.txt'},
    },
}


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


def suspend_glass(uuid):
    # Do not re-enable an in-memory Shell module when repairing its files on disk.
    disabled = extensions('disabled-extensions')
    if uuid not in disabled:
        command('gsettings', 'set', 'org.gnome.shell', 'disabled-extensions', repr(disabled + [uuid]))
    enabled = extensions('enabled-extensions')
    if uuid in enabled:
        command('gsettings', 'set', 'org.gnome.shell', 'enabled-extensions', repr([item for item in enabled if item != uuid]))
    if uuid in extensions('enabled-extensions') or uuid not in extensions('disabled-extensions'):
        raise RuntimeError('无法安全停用 GNOME 液态玻璃扩展，请保持经典背景并导出诊断包。')
    diagnostics.emit('extension.suspended', {'uuid': uuid})


def install(kind, archive):
    support = SUPPORTS[kind]
    desktop = os.environ.get('XDG_CURRENT_DESKTOP', '').lower().split(':')
    if 'gnome' not in desktop:
        raise RuntimeError('此配套扩展仅用于 GNOME 桌面')
    match = re.search(r'(\d+)', command('gnome-shell', '--version'))
    if match is None or int(match.group(1)) not in support['versions']:
        supported = '50' if kind == 'glass' else '45–50'
        raise RuntimeError(support['label'] + '适用于 GNOME ' + supported + '，当前桌面版本不支持。')
    files = package_files(archive, support, int(match.group(1)))
    for key in ('enabled-extensions', 'disabled-extensions'):
        if command('gsettings', 'writable', 'org.gnome.shell', key) != 'true':
            raise RuntimeError('系统策略禁止修改 GNOME 扩展设置，请联系系统管理员。')
        extensions(key)
    if (kind != 'glass' and command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') == 'true' and
            command('gsettings', 'writable', 'org.gnome.shell', 'disable-user-extensions') != 'true'):
        raise RuntimeError('系统策略禁止开启 GNOME 用户扩展，请联系系统管理员。')
    data_home = Path(os.environ.get('XDG_DATA_HOME') or str(Path.home() / '.local/share'))
    if not data_home.is_absolute():
        raise RuntimeError('GNOME 用户数据目录必须是绝对路径')
    data_home = data_home.resolve()
    root = (data_home / 'gnome-shell/extensions').resolve()
    root.mkdir(parents=True, exist_ok=True)
    if kind == 'glass':
        suspend_glass(support['uuid'])
    changed = deploy(root / support['uuid'], root, data_home / 'spw-island-backups', files)
    if kind == 'glass':
        print('GNOME 液态玻璃已安全停用，文件已修复。请直接导出诊断包；无需注销或重新开启扩展。')
        return
    try:
        active = enable(support['uuid'])
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(support['label'] + '已安装，但自动启用失败。请重试“安装 / 修复”，或保存工作后重新登录。' +
                           '\n' + str(error)) from error
    if changed or not active:
        print(support['label'] + '已安装，用户扩展总开关已开启。请保存工作，注销后重新登录。')
    else:
        print(support['label'] + '已就绪，无需重新安装。')


if __name__ == '__main__':
    try:
        install(sys.argv[1], sys.argv[2])
    except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        diagnostics.failure("caught.error", error)
        print(str(error))
        sys.exit(1)
