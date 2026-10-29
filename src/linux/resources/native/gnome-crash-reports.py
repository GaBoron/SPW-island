# SPDX-License-Identifier: GPL-3.0-only
"""Read selected Apport text fields without unpacking cores or executing report content."""
import os
from pathlib import Path
import re
import stat
import time


FIELDS = {
    'ProblemType': 80, 'Date': 120, 'ExecutablePath': 512, 'Signal': 80, 'SignalName': 80,
    'AssertionMessage': 2000, 'Package': 512, 'Architecture': 80, 'DistroRelease': 200,
    'StacktraceTop': 4000, 'Stacktrace': 12000, 'StacktraceAddressSignature': 2000,
    'SegvAnalysis': 2000, 'SegvReason': 2000, 'Title': 512,
}
REPORT_NAME = re.compile(r'_(?:usr_bin|usr_libexec)_gnome-shell\.\d+\.crash')
SCAN_LIMIT = 4 * 1024 * 1024


def read_report(path, deadline):
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    with os.fdopen(os.open(path, flags), 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode):
            raise OSError('crash report is not a regular file')
        fields = {}
        key = None
        scanned = 0
        stopped = 'end of file'
        while scanned < SCAN_LIMIT:
            if time.monotonic() >= deadline:
                stopped = 'collection deadline'
                break
            line = stream.readline(min(16_384, SCAN_LIMIT - scanned))
            if not line:
                break
            scanned += len(line)
            if line.startswith(b' '):
                if key in FIELDS and len(fields[key]) < FIELDS[key]:
                    fields[key] = (fields[key] + '\n' + line[1:].decode('utf-8', 'replace').rstrip())[:FIELDS[key]]
                if not line.endswith(b'\n'):
                    stopped = 'line limit or end of file'
                    break
                continue
            name, separator, value = line.partition(b':')
            key = name.decode('ascii', 'replace') if separator else None
            # Apport writes text fields before binary attachments. Stop before reading their payload.
            if separator and (key == 'CoreDump' or value.strip() == b'base64'):
                stopped = 'binary attachment skipped'
                break
            if key in FIELDS:
                fields[key] = value.decode('utf-8', 'replace').strip()[:FIELDS[key]]
            if not line.endswith(b'\n'):
                stopped = 'line limit or end of file'
                break
        else:
            stopped = 'scan limit'
        if fields.get('ExecutablePath') not in ('/usr/bin/gnome-shell', '/usr/libexec/gnome-shell'):
            return {'file': path.name, 'available': False, 'scanStopped': stopped,
                    'error': 'Shell executable not confirmed in bounded text header'}
        return {'file': path.name, 'available': True, 'modifiedAtUnix': info.st_mtime,
                'fields': fields, 'scanStopped': stopped}


def collect(deadline):
    root = Path('/var/crash')
    try:
        candidates = []
        for index, path in enumerate(root.iterdir()):
            if index >= 1024 or time.monotonic() >= deadline:
                break
            if not REPORT_NAME.fullmatch(path.name):
                continue
            info = path.lstat()
            if stat.S_ISREG(info.st_mode) and info.st_mtime >= time.time() - 7 * 86400:
                candidates.append((info.st_mtime, path))
        reports = []
        for _, path in sorted(candidates, reverse=True)[:3]:
            try:
                reports.append(read_report(path, deadline))
            except OSError as error:
                reports.append({'file': path.name, 'available': False, 'error': str(error)[:1000]})
        return {'available': True, 'reports': reports,
                'notice': 'Only selected text fields from recent Shell reports; no core, environment or command line.'}
    except OSError as error:
        return {'available': False, 'error': str(error)[:1000]}
