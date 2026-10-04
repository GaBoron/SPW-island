# SPDX-License-Identifier: GPL-3.0-only
"""Install only the optional SPW pointer extension, preserving other Shell settings."""
import ast
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tempfile
import zipfile

UUID = 'spw-island-pointer@gaboron.github.io'


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=15)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or '扩展安装失败')
    return result.stdout.strip()


def extensions(key):
    return ast.literal_eval(command('gsettings', 'get', 'org.gnome.shell', key).removeprefix('@as '))


def install(archive):
    desktop = os.environ.get('XDG_CURRENT_DESKTOP', '').lower().split(':')
    if 'gnome' not in desktop:
        raise RuntimeError('此配套扩展仅用于 GNOME 桌面')
    version = int(re.search(r'(\d+)', command('gnome-shell', '--version')).group(1))
    if version not in range(45, 51):
        raise RuntimeError('此扩展支持 GNOME 45–50；当前版本尚未验证')
    directory = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'gnome-shell/extensions' / UUID
    directory.parent.mkdir(parents=True, exist_ok=True)
    # Stage beside the destination: CLI installers can fail moving directories across /tmp and /home.
    # Only the three known companion files are installed, and a prior copy is recoverable.
    staging = Path(tempfile.mkdtemp(prefix='.spw-island-pointer-', dir=directory.parent))
    backup = None
    try:
        with zipfile.ZipFile(archive) as package:
            if set(package.namelist()) != {'extension.js', 'metadata.json', 'LICENSE'}:
                raise RuntimeError('GNOME 指针扩展包内容不正确')
            if json.loads(package.read('metadata.json'))['uuid'] != UUID:
                raise RuntimeError('GNOME 指针扩展标识不正确')
            for name in package.namelist():
                (staging / name).write_bytes(package.read(name))
        if directory.exists():
            backup = directory.parent.parent / 'spw-island-backups' / str(time.time_ns())
            backup.parent.mkdir(parents=True, exist_ok=True)
            directory.rename(backup)
        try:
            staging.rename(directory)
        except OSError:
            if backup is not None:
                backup.rename(directory)
            raise
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    enabled, disabled = extensions('enabled-extensions'), extensions('disabled-extensions')
    if UUID not in enabled:
        command('gsettings', 'set', 'org.gnome.shell', 'enabled-extensions', repr(enabled + [UUID]))
    if UUID in disabled:
        command('gsettings', 'set', 'org.gnome.shell', 'disabled-extensions', repr([item for item in disabled if item != UUID]))
    # GNOME discovers new local extensions at session startup; never restart a live Wayland desktop.
    print('GNOME 指针扩展已安装。首次安装或更新后，请保存工作并重新登录；随后开启悬停隐藏。')
    if command('gsettings', 'get', 'org.gnome.shell', 'disable-user-extensions') == 'true':
        print('当前用户扩展已全局禁用，请在 GNOME 扩展应用中启用用户扩展。')


if __name__ == '__main__':
    try:
        install(sys.argv[1])
    except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(str(error))
        sys.exit(1)
