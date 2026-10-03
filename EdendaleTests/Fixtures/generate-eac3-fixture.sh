#!/bin/bash
set -euo pipefail

# Synthetic media only. Requires an ffmpeg CLI with the eac3 and ac3 encoders,
# the sine, join, and pan filters, and the Matroska muxer.
fixture_dir="$(cd "$(dirname "$0")" && pwd)"

# Track 1: 2 s of 5.1(side) E-AC-3, a different tone on each channel.
# Track 2: 2 s of stereo AC-3, which never passes through.
# Clusters of about 0.1 s let a seek land just before its target.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'sine=frequency=300:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=400:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=500:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=60:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=700:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=800:sample_rate=48000:duration=2' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' \
  -filter_complex '[0:a][1:a][2:a][3:a][4:a][5:a]join=inputs=6:channel_layout=5.1(side):map=0.0-FL|1.0-FR|2.0-FC|3.0-LFE|4.0-SL|5.0-SR[surround];[6:a]pan=stereo|c0=c0|c1=c0[stereo]' \
  -map '[surround]' -map '[stereo]' -c:a:0 eac3 -b:a:0 192k -c:a:1 ac3 -b:a:1 96k \
  -cluster_time_limit 100 -y "$fixture_dir/decoder-eac3.mkv"
