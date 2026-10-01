#!/usr/bin/env bash
# Generates short binary test fixtures (< 1 MB each) for hermetic tests using ffmpeg.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v ffmpeg &>/dev/null; then
    echo "ffmpeg is required to generate test fixtures but was not found on PATH." >&2
    exit 1
fi

echo "Generating test fixtures..."

# 1. Short 1-second 720p video
ffmpeg -y -f lavfi -i testsrc=duration=1:size=1280x720:rate=24 \
    -c:v libx264 -pix_fmt yuv420p -t 1 video_720p.mp4

# 2. Short 1-second 1080p video
ffmpeg -y -f lavfi -i testsrc=duration=1:size=1920x1080:rate=24 \
    -c:v libx264 -pix_fmt yuv420p -t 1 video_1080p.mp4

# 3. Short 1-second stereo audio tone
ffmpeg -y -f lavfi -i sine=frequency=1000:duration=1:sample_rate=48000 \
    -c:a aac -ac 2 -t 1 audio_stereo.m4a

# 4. Multi-audio container (MKV with 2 audio tracks)
ffmpeg -y -f lavfi -i testsrc=duration=1:size=640x360:rate=24 \
    -f lavfi -i sine=frequency=440:duration=1:sample_rate=48000 \
    -f lavfi -i sine=frequency=880:duration=1:sample_rate=48000 \
    -map 0:v -map 1:a -map 2:a \
    -c:v libx264 -c:a aac \
    -metadata:s:a:0 language=eng -metadata:s:a:0 title="Main Audio" \
    -metadata:s:a:1 language=fra -metadata:s:a:1 title="Commentary" \
    -t 1 multi_audio.mkv

echo "Fixtures generated successfully."
