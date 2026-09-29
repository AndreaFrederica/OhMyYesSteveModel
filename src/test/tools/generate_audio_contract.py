"""Frozen, independently encoded/decoded audio fixtures. Requires FFmpeg; ordinary tests do not."""
from pathlib import Path
import json
import math
import struct
import subprocess

root = Path(__file__).resolve().parents[1] / "resources/audio-contract"
root.mkdir(parents=True, exist_ok=True)


def rewrite_pages(data, change):
    offset = 0
    index = 0
    while offset < len(data):
        segments = data[offset + 26]
        size = 27 + segments + sum(data[offset + 27:offset + 27 + segments])
        change(data, offset, index)
        data[offset + 22:offset + 26] = bytes(4)
        crc = 0
        for byte in data[offset:offset + size]:
            crc ^= byte << 24
            for _ in range(8):
                crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xffffffff
        struct.pack_into("<I", data, offset + 22, crc)
        offset += size
        index += 1


cases = []
for codec, rate, label, frames in [
    ("opus", 48000, "under", 191999), ("opus", 48000, "exact", 192000),
    ("opus", 48000, "long", 240001), ("opus", 48000, "origin", 60001),
    ("opus", 48000, "input-rate", 60001),
    ("vorbis", 44100, "under", 176399), ("vorbis", 44100, "exact", 176400),
    ("vorbis", 44100, "long", 220501),
]:
    name = f"{codec}-{label}"
    pcm = bytearray()
    for i in range(frames):
        for frequency in (440, 613):
            pcm += struct.pack("<h", round(10000 * math.sin(i * frequency * 2 * math.pi / rate)))
    encoded = root / (name + ".ogg")
    reference = root / (name + ".s16le")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "s16le", "-ar", str(rate), "-ac", "2", "-i", "pipe:0",
                    "-c:a", "lib" + codec, "-b:a", "96k", "-fflags", "+bitexact",
                    str(encoded)], input=pcm, check=True)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(encoded),
                    "-af", "pan=mono|c0=0.5*c0+0.5*c1", "-f", "s16le", "-c:a", "pcm_s16le",
                    str(reference)], check=True)
    data = bytearray(encoded.read_bytes())
    pre_skip = struct.unpack_from("<H", data, 28 + 10)[0] if codec == "opus" else 0
    if label == "origin":
        def origin(buf, offset, index):
            if index >= 2:
                value = struct.unpack_from("<q", buf, offset + 6)[0]
                if value >= 0: struct.pack_into("<q", buf, offset + 6, value + 96000)
        rewrite_pages(data, origin)
    elif label == "input-rate":
        def input_rate(buf, offset, index):
            if index == 0: struct.pack_into("<I", buf, offset + 28 + 12, 44100)
        rewrite_pages(data, input_rate)
    encoded.write_bytes(data)
    assert reference.stat().st_size == frames * 2, name
    cases.append(dict(id=name, codec=codec, source_channels=2, sample_rate=rate,
                      frames=frames, pre_skip=pre_skip, short_eligible=frames < rate * 4,
                      encoded=encoded.name, reference=reference.name))
(root / "manifest.json").write_text(json.dumps(dict(cases=cases), indent=2) + "\n")
