# SPDX-License-Identifier: GPL-3.0-only
"""Private SPW PipeWire monitor. Standard library only; PCM never leaves this process."""
import array
import cmath
import json
import math
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import sys
import threading
import time

SIZE = 2048
RATE = 48000
EDGES = (40, 250, 1000, 4000, 16000)


class Spectrum:
    def __init__(self):
        self.window = [.5 - .5 * math.cos(2 * math.pi * i / (SIZE - 1)) for i in range(SIZE)]
        self.reverse = [int(f'{i:011b}'[::-1], 2) for i in range(SIZE)]
        self.roots = {length: [cmath.exp(-2j * math.pi * i / length) for i in range(length // 2)]
                      for length in (2 ** n for n in range(1, 12))}
        self.reference = 0.0
        self.silent_seconds = 0.0

    def power(self, samples):
        values = [complex(samples[j] * self.window[j]) for j in self.reverse]
        for length, roots in self.roots.items():
            half = length // 2
            for start in range(0, SIZE, length):
                for i, root in enumerate(roots):
                    even = start + i
                    odd = even + half
                    a, b = values[even], values[odd] * root
                    values[even], values[odd] = a + b, a - b
        return [v.real * v.real + v.imag * v.imag for v in values[:SIZE // 2]]

    def rms(self, interleaved):
        # Combine channel power, not samples: opposite-phase stereo must not disappear.
        left, right = self.power(interleaved[::2]), self.power(interleaved[1::2])
        result = []
        for low, high in zip(EDGES, EDGES[1:]):
            start = max(1, math.ceil(low * SIZE / RATE))
            end = min(SIZE // 2, math.ceil(high * SIZE / RATE))
            power = sum((left[i] + right[i]) * .5 for i in range(start, end))
            result.append(math.sqrt(2 * power / (SIZE * 3 * (SIZE - 1) / 8)))
        return result

    def levels(self, samples):
        rms = self.rms(samples)
        peak = max(rms)
        seconds = SIZE / RATE
        if peak < .0003:
            self.silent_seconds += seconds
            if self.silent_seconds >= .5:
                self.reference = 0
            return [0.0] * 4
        self.silent_seconds = 0
        target = max(.004, peak)
        if self.reference == 0:
            self.reference = target
        else:
            tau = .25 if target > self.reference else 4.0
            self.reference += (target - self.reference) * (1 - math.exp(-seconds / tau))
        return [0.0 if value < .0003 else 1 - math.exp(-(value / (self.reference * 1.25)) ** 1.2)
                for value in rms]


def descendant_pids(root):
    parents = {}
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            # comm can contain spaces and parentheses; fields after its final ')' are stable.
            fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
            parents[int(entry.name)] = int(fields[1])
        except (OSError, ValueError, IndexError):
            pass
    owned = {root}
    while True:
        expanded = owned | {pid for pid, parent in parents.items() if parent in owned}
        if expanded == owned:
            return owned
        owned = expanded


def select_node(objects, owned):
    # ALSA clients expose PID on their Client, not necessarily on their Node.
    clients = {}
    for obj in objects:
        if obj.get('type') == 'PipeWire:Interface:Client':
            props = obj.get('info', {}).get('props', {})
            # The server-provided credential takes precedence over client-supplied identity.
            pid = props.get('pipewire.sec.pid', props.get('application.process.id'))
            clients[str(obj['id'])] = str(pid) in {str(p) for p in owned}
    nodes = []
    for obj in objects:
        if obj.get('type') != 'PipeWire:Interface:Node':
            continue
        info = obj.get('info', {})
        props = info.get('props', {})
        if props.get('media.class') != 'Stream/Output/Audio':
            continue
        # Never select a sink, source/microphone or similarly named foreign application.
        if not clients.get(str(props.get('client.id')), False):
            continue
        serial = props.get('object.serial')
        if serial is not None:
            nodes.append((info.get('state') == 'running', int(serial), str(serial)))
    # Newest active playback stream wins on reconnect. Idle/pause is still valid audio silence.
    return max(nodes)[2] if nodes else None


def discover(root):
    dump = subprocess.run(['pw-dump'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          timeout=2, check=True)
    return select_node(json.loads(dump.stdout), descendant_pids(root))


def terminate(process):
    if process is None:
        return
    process.terminate()
    try:
        process.wait(timeout=.5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=.5)
    if process.stdout:
        process.stdout.close()


def run(root):
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    def parent_pipe():
        # EOF also covers abrupt host death. Kotlin closes this pipe when capture is disabled.
        os.read(sys.stdin.fileno(), 64)
        stopped.set()
    threading.Thread(target=parent_pipe, daemon=True).start()
    if not shutil.which('pw-record') or not shutil.which('pw-dump'):
        print('FALLBACK 缺少 pw-record / pw-dump，使用模拟频谱', flush=True)
        return 1
    capture, target, selector = None, None, selectors.DefaultSelector()
    next_discovery, pending, fft, ready = 0, bytearray(), Spectrum(), False
    previous_status = None
    def status(value):
        nonlocal previous_status
        if value != previous_status:
            print(value, flush=True)
            previous_status = value
    try:
        while not stopped.is_set() and Path(f'/proc/{root}').exists():
            now = time.monotonic()
            if now >= next_discovery:
                next_discovery = now + 1
                try:
                    found = discover(root)
                except (OSError, ValueError, subprocess.SubprocessError):
                    found = None
                if capture is not None and capture.poll() is not None:
                    found = None
                if found != target:
                    if capture is not None:
                        selector.unregister(capture.stdout)
                        terminate(capture)
                    capture, target, pending, fft, ready = None, found, bytearray(), Spectrum(), False
                    if found is not None:
                        props = {'node.name': 'spw-island-spectrum', 'application.name': 'SPW Island spectrum',
                                 'node.dont-fallback': True, 'node.dont-reconnect': True,
                                 'node.passive': True, 'stream.capture.sink': True}
                        capture = subprocess.Popen(['pw-record', '--raw', '--format', 'f32', '--rate', str(RATE),
                                                    '--channels', '2', '--latency', '2048', '--target', found,
                                                    '-P', json.dumps(props), '-'], stdin=subprocess.DEVNULL,
                                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
                        selector.register(capture.stdout, selectors.EVENT_READ)
                        status('WAIT 正在连接 SPW 音频（暂用模拟频谱）')
                if target is None:
                    status('WAIT 未找到可采集的 SPW 播放流（使用模拟频谱）')
            if capture is None:
                stopped.wait(.1)
                continue
            for key, _ in selector.select(.1):
                chunk = os.read(key.fd, SIZE * 8)
                if not chunk:
                    selector.unregister(capture.stdout)
                    terminate(capture)
                    capture, target, next_discovery = None, None, 0
                    status('WAIT SPW 音频已断开（使用模拟频谱）')
                    break
                pending.extend(chunk)
                while len(pending) >= SIZE * 8:
                    samples = array.array('f', pending[:SIZE * 8])
                    del pending[:SIZE * 8]
                    if sys.byteorder != 'little':
                        samples.byteswap()
                    # Corrupt/non-finite input must never propagate into the UI.
                    values = fft.levels(samples) if all(math.isfinite(v) for v in samples) else [0.] * 4
                    if not ready:
                        ready = True
                        status('READY SPW 进程实时频谱')
                    print(','.join(f'{v:.4f}' for v in values), flush=True)
    finally:
        selector.close()
        terminate(capture)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(run(int(sys.argv[1])))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'FALLBACK 实时频谱不可用：{error}（使用模拟频谱）', flush=True)
        sys.exit(1)
