# SPDX-License-Identifier: GPL-3.0-only
"""Read bounded Shell failure evidence without enabling extensions or invoking their renderer."""
import datetime
import json
import re
import subprocess
import time


DEADLINE = time.monotonic() + 12
UUID = 'spw-island-glass@gaboron.github.io'
RELEVANT = re.compile(
    r'spw[ -]?island|gaboron|JS ERROR|segfault|assertion|fatal|SIGSEGV|SIGABRT|'
    r'Cogl|Clutter|Gjs|GPU.*(?:hang|reset)|(?:hang|reset).*GPU', re.IGNORECASE)


def query(*arguments):
    remaining = DEADLINE - time.monotonic()
    if remaining <= 0:
        return {'available': False, 'error': 'collection deadline reached'}
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, errors='replace',
                                timeout=min(2, remaining))
        if result.returncode:
            return {'available': False, 'error': (result.stderr or result.stdout)[-1200:]}
        return {'available': True, 'output': result.stdout}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'available': False, 'error': str(error)[:1200]}


def journal(*matches, user=False, limit=100):
    args = ['journalctl', '--no-pager', '--quiet', '-b', '--since=-1day', '-n', str(limit), '-o', 'json']
    if user:
        args.append('--user')
    result = query(*args, *matches)
    if not result['available']:
        return result
    entries = []
    for line in result['output'].splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        message = record.get('MESSAGE', '')
        if not isinstance(message, str):
            continue
        # Coredumps are already restricted to gnome-shell; other Shell logs need a relevant failure.
        if record.get('COREDUMP_COMM') != 'gnome-shell' and not RELEVANT.search(message):
            continue
        entries.append({key: record[key] for key in
                        ('__REALTIME_TIMESTAMP', '_PID', 'PRIORITY', 'COREDUMP_SIGNAL', 'COREDUMP_EXE')
                        if key in record})
        entries[-1]['message'] = message[:3500]
    return {'available': True, 'entries': entries[-12:]}


def collect():
    result = {
        'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'notice': 'Read-only. Current boot, last 24 hours, bounded matching Shell errors; no core files or desktop images.',
        'renderingPolicy': 'suspended',
        'shellVersion': query('gnome-shell', '--version'),
        'extension': query('gnome-extensions', 'info', UUID),
    }
    # No arbitrary configuration, environment variables, or application/window titles are collected.
    result['settings'] = {key: query('gsettings', 'get', 'org.gnome.shell', key) for key in
                          ('disable-user-extensions', 'enabled-extensions', 'disabled-extensions')}
    result['shellUserJournal'] = journal('_COMM=gnome-shell', user=True)
    result['shellJournal'] = journal('_COMM=gnome-shell')
    result['shellCoredumps'] = journal('COREDUMP_COMM=gnome-shell', limit=3)
    for key in ('shellVersion', 'extension'):
        if result[key].get('output'):
            result[key]['output'] = result[key]['output'][:2500]
    for value in result['settings'].values():
        if value.get('output'):
            value['output'] = value['output'][:2000]
    # Keep protocol stdout below its JVM read budget even with large native backtraces.
    for key in ('shellUserJournal', 'shellJournal', 'shellCoredumps'):
        while len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > 60000 and result[key].get('entries'):
            result[key]['entries'].pop(0)
            result[key]['truncated'] = True
    return result


if __name__ == '__main__':
    print(json.dumps(collect(), ensure_ascii=False))
