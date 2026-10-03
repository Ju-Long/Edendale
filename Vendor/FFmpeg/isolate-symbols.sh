#!/bin/bash
set -euo pipefail

# Combine one architecture before localizing symbols, so FFmpeg's internal
# references cannot bind to the different FFmpeg bundled in static libVLC.
INPUT="$1"
OUTPUT="$2"
HEADER="$3"
ARCH="$4"
PLATFORM="$5"
MIN_VERSION="$6"
SDK_VERSION="$7"
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/edendale-ffmpeg-symbols.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT

xcrun nm -gjU "$INPUT" | LC_ALL=C sort -u | awk '
    /^_(av|swr_|swresample_|sws_|swscale_)/ && !/^_avpriv_/ {
        print $0, "_edendale_ffmpeg" $0
    }
' > "$WORK_DIR/aliases"
test -s "$WORK_DIR/aliases"
awk '{ print $2 }' "$WORK_DIR/aliases" > "$WORK_DIR/exports"

xcrun ld -r -arch "$ARCH" \
    -platform_version "$PLATFORM" "$MIN_VERSION" "$SDK_VERSION" \
    -all_load "$INPUT" -alias_list "$WORK_DIR/aliases" \
    -o "$WORK_DIR/FFmpeg.o"
xcrun nmedit -s "$WORK_DIR/exports" "$WORK_DIR/FFmpeg.o"

# Common symbols cannot be localized by nmedit. Build with -fno-common and
# fail here if any unprefixed definitions would leak into the application.
xcrun nm -gjU "$WORK_DIR/FFmpeg.o" | LC_ALL=C sort -u > "$WORK_DIR/actual"
if ! cmp -s "$WORK_DIR/exports" "$WORK_DIR/actual"; then
    echo "error: FFmpeg symbol isolation failed; rebuild with --clean." >&2
    exit 1
fi
xcrun libtool -static -o "$OUTPUT" "$WORK_DIR/FFmpeg.o"

{
    echo '#ifndef EDENDALE_FFMPEG_ISOLATED'
    echo '#define EDENDALE_FFMPEG_ISOLATED 1'
    # Linker names include Darwin's underscore. Pragmas preserve C identifiers
    # and header feature checks such as #ifndef av_log2.
    awk '{ print "#pragma redefine_extname", substr($1, 2), $2 }' "$WORK_DIR/aliases"
    echo '#endif'
} > "$HEADER"
