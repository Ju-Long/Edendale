#!/bin/bash
set -euo pipefail

# Synthetic media only. Requires an ffmpeg CLI with libx264 and AAC encoders.
fixture_dir="$(cd "$(dirname "$0")" && pwd)"
fixture_tmp="$(mktemp -d)"
trap 'rm -rf "$fixture_tmp"' EXIT

cat > "$fixture_tmp/test.srt" <<'SRT'
1
00:00:00,200 --> 00:00:02,000
First subtitle

2
00:00:02,000 --> 00:00:04,800
Second subtitle, with comma
SRT

cat > "$fixture_tmp/test.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 160
PlayResY: 90
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,12,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,4,4,4,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.20,0:00:02.00,Default,,0,0,0,,ASS first
Dialogue: 0,0:00:02.00,0:00:04.80,Default,,0,0,0,,ASS second, with comma
ASS

ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=160x90:rate=12:duration=5' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=5' \
  -i "$fixture_tmp/test.srt" -i "$fixture_tmp/test.ass" \
  -map 0:v -map 1:a -map 2:s -map 3:s \
  -c:v libx264 -preset ultrafast -g 12 -c:a aac -c:s copy \
  -y "$fixture_dir/decoder-subtitles.mkv"
