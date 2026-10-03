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

# 5. DTS and TrueHD audio for the FFmpeg decoder's instrumented test (E.2.3).
#    These two are committed under src/androidTest/assets/fixtures, because the
#    test reads them as APK assets. FFmpeg's DTS and TrueHD encoders are
#    experimental, hence -strict -2.
ASSETS="$SCRIPT_DIR/../../androidTest/assets/fixtures"
mkdir -p "$ASSETS"
ffmpeg -y -f lavfi -i sine=frequency=1000:duration=1:sample_rate=48000 \
    -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" \
    -c:a dca -strict -2 -b:a 768k -fflags +bitexact -map_metadata -1 -f matroska \
    "$ASSETS/audio_dts_5_1.mka"
# TrueHD as 5.1 at 24 bits, as discs carry it: the 16-bit stereo stream this
# encoder writes isn't accepted by the FFmpeg 6.0 decoder in Jellyfin's build.
ffmpeg -y -f lavfi -i sine=frequency=1000:duration=1:sample_rate=48000 \
    -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0,aformat=sample_fmts=s32" \
    -c:a truehd -strict -2 -fflags +bitexact -map_metadata -1 -f matroska \
    "$ASSETS/audio_truehd_5_1.mka"

echo "Fixtures generated successfully."
