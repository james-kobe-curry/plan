#!/usr/bin/env python3
"""Generate plan's original reminder sounds using only the Python standard library.

No recordings, samples, sound fonts, or third-party compositions are used.
Run from any directory: python tools/generate_reminder_sounds.py
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 44_100
PEAK_LIMIT = 0.78
TAU = 2.0 * math.pi


def new_track(seconds: float) -> list[float]:
    return [0.0] * round(seconds * SAMPLE_RATE)


def strike(
    track: list[float],
    start: float,
    frequency: float,
    level: float,
    partials: tuple[tuple[float, float, float], ...],
    attack: float = 0.008,
) -> None:
    """Add exponentially damped sine partials (ratio, amplitude, decay seconds)."""
    begin = round(start * SAMPLE_RATE)
    for index in range(begin, len(track)):
        elapsed = (index - begin) / SAMPLE_RATE
        onset = 0.5 - 0.5 * math.cos(math.pi * min(1.0, elapsed / attack))
        value = sum(
            amplitude
            * math.exp(-elapsed / decay)
            * math.sin(TAU * frequency * ratio * elapsed)
            for ratio, amplitude, decay in partials
        )
        track[index] += level * onset * value


def morning_chime() -> list[float]:
    track = new_track(2.8)
    # Bell-like inharmonic upper partials, with a mellow ascending contour.
    bell = ((1.0, 1.0, 0.44), (2.01, 0.30, 0.24), (2.77, 0.11, 0.15))
    for start, frequency, level in ((0.025, 659.25, 1.0), (0.43, 880.0, 0.91), (0.88, 1318.51, 0.77)):
        strike(track, start, frequency, level, bell, attack=0.012)
    return track


def light_wood() -> list[float]:
    track = new_track(2.4)
    # A wood-bar-like timbre: a clear fundamental and quickly fading overtones.
    wood = ((1.0, 1.0, 0.24), (3.0, 0.21, 0.07), (5.0, 0.07, 0.045))
    for start, frequency, level in ((0.025, 523.25, 1.0), (0.35, 659.25, 0.92), (0.69, 783.99, 0.86), (1.04, 1046.50, 0.80)):
        strike(track, start, frequency, level, wood, attack=0.005)
    return track


def clear_double() -> list[float]:
    track = new_track(1.9)
    # Two separated bright tones; short enough to remain a compact notification.
    clear = ((1.0, 1.0, 0.26), (2.0, 0.34, 0.15), (3.0, 0.09, 0.085))
    strike(track, 0.025, 783.99, 1.0, clear, attack=0.006)
    strike(track, 0.48, 1174.66, 0.92, clear, attack=0.006)
    return track


def soft_echo() -> list[float]:
    track = new_track(3.6)
    # Rounded, low-register chord followed by quieter echoes, without an abrupt hit.
    soft = ((1.0, 1.0, 0.56), (2.0, 0.12, 0.28), (3.0, 0.035, 0.16))
    for echo_start, echo_level in ((0.025, 1.0), (0.70, 0.42), (1.375, 0.19)):
        for delay, frequency, level in ((0.0, 440.0, 1.0), (0.07, 554.37, 0.72), (0.14, 659.25, 0.60)):
            strike(track, echo_start + delay, frequency, level * echo_level, soft, attack=0.04)
    return track


def write_sound(path: Path, samples: list[float]) -> dict[str, str | float | int]:
    # A raised-cosine fade finishes at exact zero, avoiding a truncated-tail click.
    fade_samples = round(0.12 * SAMPLE_RATE)
    for offset in range(fade_samples):
        index = len(samples) - fade_samples + offset
        gain = 0.5 + 0.5 * math.cos(math.pi * offset / (fade_samples - 1))
        samples[index] *= gain
    peak = max(abs(value) for value in samples)
    if peak <= 0:
        raise ValueError(f"Silent sound: {path.name}")
    gain = PEAK_LIMIT / peak
    pcm = [round(value * gain * 32767) for value in samples]
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(struct.pack(f"<{len(pcm)}h", *pcm))

    # Verify the actual saved PCM file rather than only the floating-point track.
    with wave.open(str(path), "rb") as saved:
        frame_count = saved.getnframes()
        assert saved.getnchannels() == 1
        assert saved.getsampwidth() == 2
        assert saved.getframerate() == SAMPLE_RATE
        values = struct.unpack(f"<{frame_count}h", saved.readframes(frame_count))
    saved_peak = max(abs(value) for value in values) / 32768.0
    rms = math.sqrt(sum((value / 32768.0) ** 2 for value in values) / frame_count)
    assert 0.0 < saved_peak <= 0.8, (path.name, saved_peak)
    assert 0.07 < rms < 0.30, (path.name, rms)
    assert values[0] == values[-1] == 0
    return {
        "file": path.name,
        "duration_seconds": round(frame_count / SAMPLE_RATE, 3),
        "sample_rate": SAMPLE_RATE,
        "channels": 1,
        "bits_per_sample": 16,
        "peak": round(saved_peak, 6),
        "rms": round(rms, 6),
        "rms_dbfs": round(20 * math.log10(rms), 2),
        "bytes": path.stat().st_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw",
    )
    args = parser.parse_args()
    sounds = (
        ("plan_chime.wav", morning_chime),
        ("plan_wood.wav", light_wood),
        ("plan_double.wav", clear_double),
        ("plan_soft.wav", soft_echo),
    )
    report = [write_sound(args.output_dir / name, synthesize()) for name, synthesize in sounds]
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
