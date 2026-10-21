# SPDX-License-Identifier: GPL-3.0-only
"""Helper-local exception reporting; stderr is collected by the owning JVM, never protocol stdout."""
import datetime
import json
import os
import sys
import threading
import time
import traceback


class Reporter:
    def __init__(self, module):
        self.module = module
        self.failures = {}
        self.lock = threading.Lock()

    def emit(self, event, context):
        try:
            print(json.dumps({'time': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              'session': os.environ.get('SPW_ISLAND_LOG_SESSION'),
                              'pid': os.getpid(), 'module': self.module, 'event': event,
                              'thread': threading.current_thread().name, 'context': context}, ensure_ascii=False),
                  file=sys.stderr, flush=True)
        except (OSError, ValueError):
            pass  # A closed diagnostic pipe must not stop the helper or recurse through its hook.

    def failure(self, event, error=None):
        error = error or sys.exc_info()[1]
        now = time.monotonic()
        key = (event, type(error).__name__)
        with self.lock:
            previous = self.failures.get(key)
            if previous is not None:
                previous['count'] += 1
                previous['last'] = now
                if now - previous['reported'] >= 60:
                    self.emit(event + '.repeated', {'count': previous['count'],
                              'durationSeconds': now - previous['first']})
                    previous['reported'] = now
                return
            if len(self.failures) >= 128:
                self.failures.pop(next(iter(self.failures)))
            self.failures[key] = {'count': 1, 'first': now, 'last': now, 'reported': now}
            self.emit(event, {'exception': type(error).__name__,
                      'stack': ''.join(traceback.format_exception(type(error), error, error.__traceback__))[-16000:]})

    def recovered(self, event):
        with self.lock:
            keys = [key for key in self.failures if key[0] == event]
            for key in keys:
                previous = self.failures.pop(key)
                self.emit(event + '.recovered', {'count': previous['count'],
                          'durationSeconds': time.monotonic() - previous['first']})


def install(module):
    reporter = Reporter(module)
    # These handlers affect this dedicated helper only, never the SPW JVM or other plugins.
    sys.excepthook = lambda kind, error, trace: reporter.failure('uncaught', error)
    threading.excepthook = lambda args: reporter.failure('thread.uncaught', args.exc_value)
    sys.unraisablehook = lambda args: reporter.failure('callback.unraisable', args.exc_value)
    return reporter
