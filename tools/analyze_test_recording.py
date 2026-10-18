#!/usr/bin/env python3
"""Inspect recordings of the internal color/tone fixtures, never arbitrary screen content.

Usage: analyze_test_recording.py sample.mp4 --times 1 4 7 --output-dir evidence/device/frames
Requires ffmpeg, ffprobe, NumPy and Pillow. Outputs only dimensions, corner colors and tone energy.
"""
import argparse
import io
import json
from pathlib import Path
import subprocess
import numpy as np
from PIL import Image

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("video", type=Path)
p.add_argument("--times", nargs="+", type=float, required=True)
p.add_argument("--output-dir", type=Path, required=True)
a = p.parse_args()
a.output_dir.mkdir(parents=True, exist_ok=True)
meta = json.loads(
    subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(a.video),
        ]
    )
)
video = next(s for s in meta["streams"] if s["codec_type"] == "video")
print(
    json.dumps(
        {
            "size": [video["width"], video["height"]],
            "duration": meta["format"]["duration"],
        }
    )
)
frames = json.loads(
    subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=best_effort_timestamp_time",
            "-of",
            "json",
            str(a.video),
        ]
    )
)["frames"]
points = sorted(
    float(f["best_effort_timestamp_time"])
    for f in frames
    if "best_effort_timestamp_time" in f
)
for t in a.times:
    # Samsung emits sparse video frames for a static window; players hold the last frame.
    point = max((point for point in points if point <= t), default=points[0])
    png = subprocess.check_output(
        [
            "ffmpeg",
            "-v",
            "error",
            "-ss",
            str(max(0, point - 0.001)),
            "-i",
            str(a.video),
            "-frames:v",
            "1",
            "-f",
            "image2pipe",
            "-vcodec",
            "png",
            "-threads",
            "1",
            "-",
        ]
    )
    im = Image.open(io.BytesIO(png)).convert("RGB")
    im.save(a.output_dir / f"{t:g}s.png")
    pixels = np.asarray(im)
    corners = [
        pixels[int(im.height * y), int(im.width * x)].tolist()
        for x, y in ((0.1, 0.1), (0.9, 0.1), (0.1, 0.9), (0.9, 0.9))
    ]
    print(json.dumps({"time": t, "source_frame_time": point, "corners_rgb": corners}))
if any(s["codec_type"] == "audio" for s in meta["streams"]):
    raw = subprocess.check_output(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(a.video),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "48000",
            "-f",
            "f32le",
            "-",
        ]
    )
    signal = np.frombuffer(raw, dtype="<f4")
    for t in a.times:
        chunk = signal[int(t * 48000) : int((t + 1) * 48000)]
        if len(chunk) < 24000:
            continue
        spec = np.abs(np.fft.rfft(chunk * np.hanning(len(chunk))))
        frequencies = np.fft.rfftfreq(len(chunk), 1 / 48000)
        bands = {
            str(hz): float(np.max(spec[np.abs(frequencies - hz) < 5]))
            for hz in (440, 880)
        }
        print(
            json.dumps(
                {
                    "audio_time": t,
                    "tone_peak": bands,
                    "rms": float(np.sqrt(np.mean(chunk**2))),
                }
            )
        )
