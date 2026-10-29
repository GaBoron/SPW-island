# SPDX-License-Identifier: GPL-3.0-only
"""Locate the faulting instruction using bounded ELF notes, without exporting process memory."""
import base64
import itertools
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time
import zlib


PREFIX_LIMIT = 4 * 1024 * 1024
COMPRESSED_LIMIT = 2 * 1024 * 1024


def compressed_blocks(stream, deadline):
    consumed = 0
    while consumed < COMPRESSED_LIMIT:
        if time.monotonic() >= deadline:
            raise ValueError('core note collection deadline reached')
        line = stream.readline(262_144)
        if not line.startswith(b' ') or not line.endswith(b'\n'):
            return
        consumed += len(line)
        if consumed > COMPRESSED_LIMIT:
            raise ValueError('compressed core prefix budget exceeded')
        block = base64.b64decode(line.strip(), validate=True)
        # Small decoder inputs bound transient expansion before the prefix limit is checked.
        for start in range(0, len(block), 32):
            if time.monotonic() >= deadline:
                raise ValueError('core note collection deadline reached')
            yield block[start:start + 32]
    raise ValueError('compressed core prefix budget exceeded')


def decoded_blocks(stream, deadline):
    source = iter(compressed_blocks(stream, deadline))
    first = next(source, b'')
    if first.startswith(b'\x28\xb5\x2f\xfd'):
        try:
            from compression import zstd
        except ImportError as error:
            raise ValueError('this system Python has no standard-library zstd decoder') from error
        decoder = zstd.ZstdDecompressor()
        for chunk in itertools.chain((first,), source):
            output = decoder.decompress(chunk, max_length=65_536)
            yield output
            while not decoder.needs_input and not decoder.eof:
                if time.monotonic() >= deadline:
                    raise ValueError('core note collection deadline reached')
                yield decoder.decompress(b'', max_length=65_536)
    else:
        # The streaming zlib API accepts both gzip and legacy zlib-wrapped Apport cores.
        decoder = zlib.decompressobj(zlib.MAX_WBITS | 32)
        for chunk in itertools.chain((first,), source):
            yield decoder.decompress(chunk, max_length=65_536)
            while decoder.unconsumed_tail:
                if time.monotonic() >= deadline:
                    raise ValueError('core note collection deadline reached')
                yield decoder.decompress(decoder.unconsumed_tail, max_length=65_536)


def note_ranges(data):
    if len(data) < 64:
        return [], 64
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', data, 16) != (4, 62):
        raise ValueError('only little-endian Linux x86-64 ELF cores are supported')
    offset = struct.unpack_from('<Q', data, 32)[0]
    stride, count = struct.unpack_from('<HH', data, 54)
    if stride != 56 or not 0 < count <= 4096 or offset < 64:
        raise ValueError('invalid ELF program header table')
    header_end = offset + stride * count
    if header_end > PREFIX_LIMIT:
        raise ValueError('ELF program headers exceed prefix budget')
    if len(data) < header_end:
        return [], header_end
    ranges = []
    for index in range(count):
        header = struct.unpack_from('<IIQQQQQQ', data, offset + stride * index)
        if header[0] == 4 and header[5]:  # PT_NOTE; PT_LOAD memory is not inspected.
            start, size = header[2], header[5]
            if start < header_end or start + size > PREFIX_LIMIT:
                raise ValueError('ELF notes exceed prefix budget')
            ranges.append((start, start + size))
    if not ranges:
        raise ValueError('core contains no ELF notes')
    return ranges, max(end for _, end in ranges)


def notes(data, ranges):
    for start, end in ranges:
        cursor = start
        while cursor + 12 <= end:
            namesize, size, kind = struct.unpack_from('<III', data, cursor)
            name_start = cursor + 12
            body_start = name_start + (namesize + 3) // 4 * 4
            next_note = body_start + (size + 3) // 4 * 4
            if next_note > end:
                raise ValueError('truncated ELF note')
            if data[name_start:name_start + namesize].rstrip(b'\0') == b'CORE':
                yield kind, data[body_start:body_start + size]
            cursor = next_note


def mapping_for(address, data):
    if len(data) < 16:
        return None
    count, page_size = struct.unpack_from('<QQ', data)
    if count > 4096 or not 0 < page_size <= 1_048_576 or 16 + count * 24 > len(data):
        raise ValueError('invalid core file mapping note')
    names = data[16 + count * 24:].split(b'\0')
    if len(names) < count:
        raise ValueError('truncated core mapping names')
    for index in range(count):
        left, right, pages = struct.unpack_from('<QQQ', data, 16 + index * 24)
        if left <= address < right:
            return names[index].decode('utf-8', 'replace'), pages * page_size + address - left
    return None


def symbol_hint(path, file_offset, deadline):
    tool = shutil.which('addr2line')
    if not tool or time.monotonic() >= deadline:
        return {'available': False, 'reason': 'optional symbol tool unavailable or collection deadline reached'}
    try:
        binary = Path(path).resolve(strict=True)
        if not binary.is_file() or not any(binary.is_relative_to(root) for root in ('/usr/lib', '/usr/bin', '/lib')):
            return {'available': False, 'reason': 'mapped file is outside system binary directories'}
        with binary.open('rb') as stream:
            header = stream.read(64)
            if header[:6] != b'\x7fELF\x02\x01' or len(header) != 64:
                raise ValueError('mapped object is not a supported ELF binary')
            offset = struct.unpack_from('<Q', header, 32)[0]
            stride, count = struct.unpack_from('<HH', header, 54)
            if stride != 56 or count > 512:
                raise ValueError('invalid mapped object program headers')
            stream.seek(offset)
            table = stream.read(stride * count)
        for index in range(count):
            segment = struct.unpack_from('<IIQQQQQQ', table, index * stride)
            if segment[0] == 1 and segment[2] <= file_offset < segment[2] + segment[5]:
                address = segment[3] + file_offset - segment[2]
                result = subprocess.run([tool, '-f', '-C', '-e', str(binary), hex(address)],
                                        capture_output=True, text=True, errors='replace',
                                        env={**os.environ, 'DEBUGINFOD_URLS': ''},
                                        timeout=min(1, max(.01, deadline - time.monotonic())))
                return {'available': result.returncode == 0, 'objectAddress': hex(address),
                        'output': result.stdout[:1200], 'error': result.stderr[:500],
                        'evidence': 'current installed binary; identity not verified against crash'}
        return {'available': False, 'reason': 'instruction offset is outside mapped ELF load segments'}
    except (OSError, ValueError, struct.error, subprocess.TimeoutExpired) as error:
        return {'available': False, 'reason': str(error)[:500]}


def parse_location(data, ranges, deadline):
    address = None
    mappings = None
    result = {'available': True, 'evidence': 'first thread in kernel ELF core notes; not a backtrace'}
    for kind, body in notes(data, ranges):
        if kind == 1 and address is None and len(body) >= 248:  # NT_PRSTATUS, Linux x86-64 ABI.
            # elf_prstatus has 112 bytes before registers; RIP is register 16 in user_regs_struct.
            address = struct.unpack_from('<Q', body, 112 + 16 * 8)[0]
            result['threadPid'] = struct.unpack_from('<i', body, 32)[0]
            result['instructionPointer'] = hex(address)
        elif kind == 0x46494c45:  # NT_FILE
            mappings = body
        elif kind == 0x53494749 and len(body) >= 24:  # NT_SIGINFO
            result['signal'], _, result['signalCode'] = struct.unpack_from('<iii', body)
            if result['signal'] == 11 and result['signalCode'] > 0:
                result['faultAddress'] = hex(struct.unpack_from('<Q', body, 16)[0])
    if address is None:
        raise ValueError('first thread instruction pointer is unavailable')
    mapping = mapping_for(address, mappings) if mappings is not None else None
    if mapping:
        path, offset = mapping
        result['module'] = Path(path).name[:200]
        result['moduleFileOffset'] = hex(offset)
        result['symbolHint'] = symbol_hint(path, offset, deadline)
    else:
        result['mappingAvailable'] = False
    return result


def locate(stream, deadline):
    try:
        data = bytearray()
        decoded = decoded_blocks(stream, deadline)
        try:
            for block in decoded:
                if time.monotonic() >= deadline:
                    raise ValueError('core note collection deadline reached')
                data.extend(block[:PREFIX_LIMIT - len(data)])
                ranges, required = note_ranges(data)
                if ranges and len(data) >= required:
                    return parse_location(data, ranges, deadline)
                if len(data) >= PREFIX_LIMIT:
                    raise ValueError('ELF note prefix budget exceeded')
        finally:
            decoded.close()
        raise ValueError('compressed core ended before ELF notes were complete')
    except Exception as error:
        # A missing optional decoder or malformed report cannot cancel the text/journal export.
        return {'available': False, 'reason': str(error)[:800]}
