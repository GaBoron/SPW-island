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
    r'spw[ -]?island|gaboron|gnome-shell|JS ERROR|segfault|assert|fatal|SIGSEGV|SIGABRT|'
    r'Cogl|Clutter|Gjs|Mutter|EGL|GLSL|shader|GL error|OpenGL|GPU|vmwgfx|SVGA|VMware|llvmpipe|out of memory|oom-kill|'
    r'GPU.*(?:hang|reset)|(?:hang|reset).*GPU', re.IGNORECASE)
SHELL_FILTER = (r'(?i)spw[ -]?island|gaboron|JS ERROR|segfault|SIGSEGV|SIGABRT|assert|fatal|'
                r'Cogl|Clutter|Mutter|EGL|GLSL|shader|GL error|OpenGL|vmwgfx|GPU|out of memory|oom-kill')


def query(*arguments, no_matches_ok=False):
    remaining = DEADLINE - time.monotonic()
    if remaining <= 0:
        return {'available': False, 'error': 'collection deadline reached'}
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, errors='replace',
                                timeout=min(2, remaining))
        no_matches = (no_matches_ok and result.returncode == 1 and not result.stderr.strip() and
                      result.stdout.strip() in ('', '-- No entries --'))
        if result.returncode and not no_matches:
            return {'available': False, 'error': (result.stderr or result.stdout or
                    'command exited with status ' + str(result.returncode))[-1200:]}
        return {'available': True, 'output': result.stdout, 'warning': result.stderr[-1200:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'available': False, 'error': str(error)[:1200]}


def journal(*matches, user=False, limit=250, relevant_only=False, priority='warning',
            grep=None, since=None, until=None):
    # An earlier Shell crash may belong to an earlier boot; do not implicitly select the current one.
    args = ['journalctl', '--no-pager', '--since=' + (since or '-2days'), '-n', str(limit), '-o', 'json']
    if until:
        args.append('--until=' + until)
    if grep:
        args.extend(['--grep', grep])
    if priority:
        args.extend(['-p', priority])
    if user:
        args.append('--user')
    result = query(*args, *matches, no_matches_ok=grep is not None)
    if not result['available']:
        return result
    entries = []
    repeated = {}
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
        # Repeated pointer-valued warnings must not crowd native failures out of the export.
        signature = (record.get('_BOOT_ID'), record.get('_PID'), re.sub(r'0x[0-9a-fA-F]+', '<address>', message))
        if signature in repeated:
            entry = repeated[signature]
            entry['occurrences'] += 1
            entry['lastTimestamp'] = record.get('__REALTIME_TIMESTAMP')
            continue
        entry = {key: record[key] for key in
                 ('__REALTIME_TIMESTAMP', '_BOOT_ID', '_PID', 'PRIORITY', 'COREDUMP_SIGNAL', 'COREDUMP_EXE')
                 if key in record}
        entry.update({'message': message[:3500], 'occurrences': 1})
        entries.append(entry)
        repeated[signature] = entry
    # Prefer native failures and this companion over unrelated extension warnings.
    entries.sort(key=lambda entry: (bool(re.search(r'spw[ -]?island|gaboron|segfault|assertion|fatal|SIGSEGV|SIGABRT',
                                                  entry['message'], re.IGNORECASE)),
                                    entry.get('PRIORITY', '7') in ('0', '1', '2', '3'),
                                    entry.get('__REALTIME_TIMESTAMP', '')))
    return {'available': True, 'entries': entries[-20:], 'uniqueRecords': len(entries),
            'matchingRecords': sum(entry['occurrences'] for entry in entries),
            'recordLimit': limit, 'recordLimitReached': len(lines) >= limit,
            'journalNotice': result.get('warning', '')}


def crash_windows(reports):
    windows = []
    for report in reports[:3]:
        try:
            timestamp = time.mktime(time.strptime(report['fields']['Date'], '%a %b %d %H:%M:%S %Y'))
        except (KeyError, ValueError, OverflowError):
            continue
        windows.append({'report': report['file'], 'crashedAtUnix': timestamp,
                        'journal': journal('_COMM=gnome-shell', priority=None, limit=500,
                                           grep=SHELL_FILTER, since='@' + str(int(timestamp - 30)),
                                           until='@' + str(int(timestamp + 30)))})
    return windows


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
        'notice': 'Read-only. Focused crash windows, bounded ELF fault metadata and recent logs; no core memory or desktop images.',
        'renderingPolicy': 'suspended',
        'shellVersion': query('gnome-shell', '--version'),
        'extension': query('gnome-extensions', 'info', UUID),
    }
    # No arbitrary configuration, environment variables, or application/window titles are collected.
    result['settings'] = {key: query('gsettings', 'get', 'org.gnome.shell', key) for key in
                          ('disable-user-extensions', 'enabled-extensions', 'disabled-extensions')}
    # Native failure reports take priority over auxiliary queries if the deadline is reached.
    locate_core = runpy.run_path(sys.argv[2])['locate']
    result['apport'] = runpy.run_path(sys.argv[1])['collect'](DEADLINE, locate_core)
    result['crashWindows'] = crash_windows(result['apport'].get('reports', []))
    result['coreHandler'] = text_file('/proc/sys/kernel/core_pattern')
    result['currentBoot'] = text_file('/proc/sys/kernel/random/boot_id')
    result['virtualization'] = query('systemd-detect-virt')
    result['graphicsDevices'] = graphics_devices()
    result['shellUserJournal'] = journal('_COMM=gnome-shell', user=True, grep=SHELL_FILTER)
    result['shellJournal'] = journal('_COMM=gnome-shell', grep=SHELL_FILTER)
    result['shellCoredumps'] = journal('COREDUMP_COMM=gnome-shell', limit=3, priority=None)
    result['kernelGraphicsJournal'] = journal('_TRANSPORT=kernel', relevant_only=True,
                                             grep=r'(?i)gnome-shell|vmwgfx|SVGA|GPU|out of memory|oom-kill')
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
    for window in reversed(result['crashWindows']):
        while len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 190000 and window['journal'].get('entries'):
            window['journal']['entries'].pop(0)
            window['journal']['truncated'] = True
    # Field limits bound each report; keep the newest if several large reports exceed the output budget.
    while len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 190000 and result['apport'].get('reports'):
        result['apport']['reports'].pop()
        result['apport']['truncated'] = True
    return result


if __name__ == '__main__':
    print(json.dumps(collect(), ensure_ascii=False))
