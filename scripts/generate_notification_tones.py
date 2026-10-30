#!/usr/bin/env python3
"""Reproduce the original notification WAV assets using only Python's standard library.

SPDX-License-Identifier: AGPL-3.0-only
No recordings or third-party samples are used. Mono PCM16, 48 kHz; peak < -6 dBFS.
"""
import math
import struct
import wave
from pathlib import Path

RATE = 48000
OUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"


def tone(name, notes, duration):
    samples = []
    for i in range(round(duration * RATE)):
        time = i / RATE
        value = 0.0
        for start, frequency, gain in notes:
            age = time - start
            if age >= 0:
                envelope = min(age / 0.008, 1.0) * math.exp(-age * 10)
                value += gain * envelope * (math.sin(2 * math.pi * frequency * age)
                         + 0.15 * math.sin(2 * math.pi * frequency * 2 * age))
        # End with a smooth 30 ms fade, retaining a bounded peak without clipping.
        value *= min(1.0, (duration - time) / 0.03)
        samples.append(round(max(-0.49, min(0.49, value)) * 32767))
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / name), "wb") as target:
        target.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        target.writeframes(struct.pack("<" + "h" * len(samples), *samples))


if __name__ == "__main__":
    tone("notification_ding_v1.wav", [(0.0, 1046.502, 0.44)], 0.55)
    tone("notification_pulse_v1.wav", [(0.0, 659.255, 0.35), (0.13, 783.991, 0.35)], 0.65)
