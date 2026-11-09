"""生成用于本地验收的合成活动，不包含真实骑行路线或账号数据。"""
from datetime import datetime, timezone
from pathlib import Path
import math
import struct


def crc16(data: bytes) -> int:
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0xA001 if crc & 1 else 0)
    return crc


def definition(local: int, global_id: int, fields: list[tuple[int, int, int]]) -> bytes:
    return bytes([0x40 | local, 0, 0]) + struct.pack('<H', global_id) + bytes([len(fields)]) + bytes(v for field in fields for v in field)


def sample() -> bytes:
    start = int(datetime(2026, 11, 1, 8, tzinfo=timezone.utc).timestamp()) - 631065600
    data = bytearray(definition(0, 0, [(0, 1, 0), (1, 2, 0x84)]))
    data.extend(bytes([0, 4]) + struct.pack('<H', 1))
    fields = [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (2, 2, 0x84), (6, 2, 0x84), (5, 4, 0x86), (3, 1, 2), (4, 1, 2), (7, 2, 0x84)]
    data.extend(definition(1, 20, fields))
    for index in range(73):
        angle = index * math.tau / 72
        lat = int((0.02 * math.sin(angle)) * (2**31) / 180)
        lon = int((0.03 * math.cos(angle)) * (2**31) / 180)
        altitude = int((500 + 20 + 10 * math.sin(angle * 2)) * 5)
        data.append(1)
        data.extend(struct.pack('<IiiHHIBBH', start + index * 50, lat, lon, altitude, 11721, round(index / 72 * 4219500), 148, 85, 210))
    data.extend(definition(2, 18, [(2, 4, 0x86), (5, 1, 0), (7, 4, 0x86), (8, 4, 0x86), (9, 4, 0x86), (16, 1, 2), (20, 2, 0x84), (22, 2, 0x84), (253, 4, 0x86)]))
    data.append(2)
    data.extend(struct.pack('<IBIIIBHHI', start, 2, 3600000, 3500000, 4219500, 148, 210, 120, start + 3600))
    header = struct.pack('<BBHI4s', 14, 0x10, 2100, len(data), b'.FIT')
    content = header + struct.pack('<H', crc16(header)) + data
    return content + struct.pack('<H', crc16(content))


if __name__ == '__main__':
    output = Path(__file__).resolve().parents[1] / '.cache' / 'samples' / 'sample-ride.fit'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(sample())
    print(output)
