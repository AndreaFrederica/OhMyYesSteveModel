"""Independent synthetic Opus/Vorbis goldens. Requires ffmpeg with libopus/libvorbis."""
from pathlib import Path
import math
import struct
import subprocess

root = Path(__file__).resolve().parent
for codec in ("opus", "vorbis"):
    for channels, label in ((1, "mono"), (2, "stereo")):
        pcm = bytearray()
        for i in range(60001):
            for channel in range(channels):
                sample = round(10000 * math.sin(i * (440 + 173 * channel) * 2 * math.pi / 48000))
                pcm += struct.pack("<h", sample)
        name = f"{codec}-{label}"
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "s16le", "-ar", "48000", "-ac", str(channels), "-i", "pipe:0",
                        "-c:a", "lib" + codec, "-b:a", "96k", "-fflags", "+bitexact",
                        str(root / (name + ".ogg"))], input=pcm, check=True)
        command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(root / (name + ".ogg"))]
        if channels == 2:
            command += ["-af", "pan=mono|c0=0.5*c0+0.5*c1"]
        command += ["-f", "s16le", "-c:a", "pcm_s16le", str(root / (name + ".pcm"))]
        subprocess.run(command, check=True)
