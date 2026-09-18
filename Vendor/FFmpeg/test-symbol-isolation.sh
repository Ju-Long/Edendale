#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/edendale-isolation-check.XXXXXX")
trap 'rm -rf "$CHECK_DIR"' EXIT
ARCH=$(uname -m)
SDK_VERSION=$(xcrun --sdk macosx --show-sdk-version)

# Simulate two libraries defining the same API and mutable internal state.
cat > "$CHECK_DIR/ffmpeg.c" <<'EOF'
int ff_shared;
int av_test(void) { return ++ff_shared; }
EOF
cat > "$CHECK_DIR/vlc.c" <<'EOF'
int ff_shared = 100;
int av_test(void) { return ++ff_shared; }
int vlc_test(void) { return av_test(); }
EOF
cat > "$CHECK_DIR/main.c" <<'EOF'
#include "namespace.h"
int av_test(void);
int vlc_test(void);
int main(void) {
    return av_test() == 1 && vlc_test() == 101 &&
        av_test() == 2 && vlc_test() == 102 ? 0 : 1;
}
EOF
xcrun clang -mmacosx-version-min=15.0 -fno-common -c "$CHECK_DIR/ffmpeg.c" -o "$CHECK_DIR/ffmpeg.o"
xcrun clang -mmacosx-version-min=15.0 -c "$CHECK_DIR/vlc.c" -o "$CHECK_DIR/vlc.o"
xcrun libtool -static -o "$CHECK_DIR/input.a" "$CHECK_DIR/ffmpeg.o"
xcrun libtool -static -o "$CHECK_DIR/vlc.a" "$CHECK_DIR/vlc.o"
bash "$SCRIPT_DIR/isolate-symbols.sh" "$CHECK_DIR/input.a" "$CHECK_DIR/isolated.a" \
    "$CHECK_DIR/namespace.h" "$ARCH" macos 15.0 "$SDK_VERSION"

# Link order must not determine either library's behavior.
xcrun clang "$CHECK_DIR/main.c" "$CHECK_DIR/vlc.a" "$CHECK_DIR/isolated.a" -o "$CHECK_DIR/check"
"$CHECK_DIR/check"
xcrun clang "$CHECK_DIR/main.c" "$CHECK_DIR/isolated.a" "$CHECK_DIR/vlc.a" -o "$CHECK_DIR/check"
"$CHECK_DIR/check"

# A cached archive with common definitions must be rejected, not shipped.
xcrun clang -mmacosx-version-min=15.0 -fcommon -c "$CHECK_DIR/ffmpeg.c" -o "$CHECK_DIR/common.o"
xcrun libtool -static -o "$CHECK_DIR/common.a" "$CHECK_DIR/common.o"
if bash "$SCRIPT_DIR/isolate-symbols.sh" "$CHECK_DIR/common.a" "$CHECK_DIR/rejected.a" \
    "$CHECK_DIR/rejected.h" "$ARCH" macos 15.0 "$SDK_VERSION" 2> "$CHECK_DIR/rejection.log"; then
    echo 'error: A common symbol escaped isolation.' >&2
    exit 1
fi
grep -q 'FFmpeg symbol isolation failed' "$CHECK_DIR/rejection.log"
echo 'Symbol isolation passed: both link orders preserve separate APIs and state; common symbols are rejected.'
