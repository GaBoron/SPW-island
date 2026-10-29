# SPDX-License-Identifier: GPL-3.0-only
"""Read bounded Shell failure evidence without enabling extensions or invoking their renderer."""
import datetime
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys
import time


DEADLINE = time.monotonic() + 16
UUID = 'spw-island-glass@gaboron.github.io'
RELEVANT = re.compile(
    r'spw[ -]?island|gaboron|gnome-shell|JS ERROR|segfault|assertion|fatal|SIGSEGV|SIGABRT|'
    r'Cogl|Clutter|Gjs|Mutter|EGL|vmwgfx|SVGA|VMware|llvmpipe|out of memory|oom-kill|'
    r'GPU.*(?:hang|reset)|(?:hang|reset).*GPU', re.IGNORECASE)


def query(*arguments):
    remaining = DEADLINE - time.monotonic()
    if remaining <= 0:
        return {'available': False, 'error': 'collection deadline reached'}
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, errors='replace',
                                timeout=min(2, remaining))
        if result.returncode:
            return {'available': False, 'error': (result.stderr or result.stdout)[-1200:]}
        return {'available': True, 'output': result.stdout, 'warning': result.stderr[-1200:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'available': False, 'error': str(error)[:1200]}


def journal(*matches, user=False, limit=250, relevant_only=False, priority='warning'):
    # An earlier Shell crash may belong to an earlier boot; do not implicitly select the current one.
    args = ['journalctl', '--no-pager', '--since=-2days', '-n', str(limit), '-o', 'json']
    if priority:
        args.extend(['-p', priority])
    if user:
        args.append('--user')
    result = query(*args, *matches)
    if not result['available']:
        return result
    entries = []
    lines = result['output'].splitlines()
    for line in lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        message = record.get('MESSAGE', '')
        if not isinstance(message, str):
            continue
        # Coredumps are already restricted to gnome-shell; other Shell logs need a relevant failure.
        important = str(record.get('PRIORITY', '7')) in ('0', '1', '2', '3')
        if (record.get('COREDUMP_COMM') != 'gnome-shell' and not RELEVANT.search(message) and
                (relevant_only or not important)):
            continue
        entries.append({key: record[key] for key in
                        ('__REALTIME_TIMESTAMP', '_BOOT_ID', '_PID', 'PRIORITY', 'COREDUMP_SIGNAL', 'COREDUMP_EXE')
                        if key in record})
        entries[-1]['message'] = message[:3500]
    # Prefer native failures and this companion over unrelated extension warnings.
    entries.sort(key=lambda entry: (bool(re.search(r'spw[ -]?island|gaboron|segfault|assertion|fatal|SIGSEGV|SIGABRT',
                                                  entry['message'], re.IGNORECASE)),
                                    entry.get('PRIORITY', '7') in ('0', '1', '2', '3'),
                                    entry.get('__REALTIME_TIMESTAMP', '')))
    return {'available': True, 'entries': entries[-20:], 'matchingRecords': len(entries),
            'recordLimit': limit, 'recordLimitReached': len(lines) >= limit,
            'journalNotice': result.get('warning', '')}


def text_file(path, limit=2000):
    try:
        with open(path, encoding='utf-8', errors='replace') as stream:
            return {'available': True, 'output': stream.read(limit).strip()}
    except OSError as error:
        return {'available': False, 'error': str(error)[:1000]}


def graphics_devices():
    try:
        devices = []
        for path in sorted(Path('/sys/class/drm').glob('card[0-9]*/device/uevent'))[:4]:
            result = text_file(path)
            if result['available']:
                result['output'] = '\n'.join(line for line in result['output'].splitlines()
                                             if line.startswith(('DRIVER=', 'PCI_ID=')))
            devices.append(result)
        return {'available': True, 'devices': devices}
    except OSError as error:
        return {'available': False, 'error': str(error)[:1000]}


def collect():
    result = {
        'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'notice': 'Read-only. Last 48 hours across boots plus recent Apport text fields; no cores or desktop images.',
        'renderingPolicy': 'suspended',
        'shellVersion': query('gnome-shell', '--version'),
        'extension': query('gnome-extensions', 'info', UUID),
    }
    # No arbitrary configuration, environment variables, or application/window titles are collected.
    result['settings'] = {key: query('gsettings', 'get', 'org.gnome.shell', key) for key in
                          ('disable-user-extensions', 'enabled-extensions', 'disabled-extensions')}
    # Native failure reports take priority over auxiliary queries if the deadline is reached.
    result['apport'] = runpy.run_path(sys.argv[1])['collect'](DEADLINE)
    result['coreHandler'] = text_file('/proc/sys/kernel/core_pattern')
    result['currentBoot'] = text_file('/proc/sys/kernel/random/boot_id')
    result['virtualization'] = query('systemd-detect-virt')
    result['graphicsDevices'] = graphics_devices()
    result['shellUserJournal'] = journal('_COMM=gnome-shell', user=True)
    result['shellJournal'] = journal('_COMM=gnome-shell')
    result['shellCoredumps'] = journal('COREDUMP_COMM=gnome-shell', limit=3, priority=None)
    result['kernelGraphicsJournal'] = journal('_TRANSPORT=kernel', relevant_only=True)
    # Per-user and system views often contain the same records; preserve the budget for unique evidence.
    system_keys = {(entry.get('__REALTIME_TIMESTAMP'), entry['message'])
                   for entry in result['shellJournal'].get('entries', [])}
    if result['shellUserJournal'].get('entries'):
        result['shellUserJournal']['entries'] = [entry for entry in result['shellUserJournal']['entries']
                                                if (entry.get('__REALTIME_TIMESTAMP'), entry['message']) not in system_keys]
    for key in ('shellVersion', 'extension'):
        if result[key].get('output'):
            result[key]['output'] = result[key]['output'][:2500]
    for value in result['settings'].values():
        if value.get('output'):
            value['output'] = value['output'][:2000]
    # Keep protocol stdout below its JVM read budget even with large native backtraces.
    for key in ('shellUserJournal', 'kernelGraphicsJournal', 'shellJournal', 'shellCoredumps'):
        while len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 190000 and result[key].get('entries'):
            result[key]['entries'].pop(0)
            result[key]['truncated'] = True
    # Field limits bound each report; keep the newest if several large reports exceed the output budget.
    while len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 190000 and result['apport'].get('reports'):
        result['apport']['reports'].pop()
        result['apport']['truncated'] = True
    return result


if __name__ == '__main__':
    print(json.dumps(collect(), ensure_ascii=False))
