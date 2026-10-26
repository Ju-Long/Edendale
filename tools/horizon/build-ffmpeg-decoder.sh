#!/usr/bin/env bash
#
# Builds Media3's FFmpeg decoder library (libffmpegJNI.so) over an LGPL-2.1
# FFmpeg for the Meta Quest build (HORIZON.md G.1, decisions D13 and D14).
#
# What it does, in order:
#   1. Clones Media3 at the tag that matches gradle/libs.versions.toml and
#      checks that the decoder classes vendored under src/horizon/java are
#      byte-identical to that tag's.
#   2. Clones FFmpeg release/6.0 at Jellyfin's pin (6.0.2, 831958d).
#   3. Runs Media3's build_ffmpeg.sh unmodified, with Jellyfin's decoder list,
#      for armeabi-v7a, arm64-v8a, x86, and x86_64.
#   4. Stops unless FFmpeg's config.h says "LGPL version 2.1 or later".
#   5. Builds libffmpegJNI.so for the four ABIs with the SDK's CMake 3.22.1
#      and NDK r26b, strips it, and installs it under src/horizon/jniLibs/,
#      which is gitignored: the library is built, never committed (D14).
#
# Needs: the Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in
# local.properties) with "ndk;26.1.10909125" and "cmake;3.22.1" installed
# (`sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`), git, and the FFmpeg
# build's usual tools (make, pkg-config is not needed). About 75 s on an
# Apple-silicon Mac for one ABI; the four take a few minutes.
#
# Usage: tools/horizon/build-ffmpeg-decoder.sh [--rebuild]
#   --rebuild   configure and build FFmpeg again even if its libraries exist.
#
# Environment overrides: ANDROID_NDK_HOME, MEDIA3_GIT_URL, FFMPEG_GIT_URL.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/build/ffmpeg-decoder"
OUT="$ROOT/src/horizon/jniLibs"
VENDORED="$ROOT/src/horizon/java/androidx/media3/decoder/ffmpeg"

NDK_VERSION="26.1.10909125"           # NDK r26b, the version Media3 tests build_ffmpeg.sh with.
CMAKE_VERSION="3.22.1"                # The SDK's CMake, with its Ninja.
FFMPEG_COMMIT="831958d0b1f000cfd297847561071c5e43903331"   # release/6.0 at 6.0.2, Jellyfin's pin.
FFMPEG_BRANCH="release/6.0"
MEDIA3_GIT_URL="${MEDIA3_GIT_URL:-https://github.com/androidx/media.git}"
FFMPEG_GIT_URL="${FFMPEG_GIT_URL:-https://git.ffmpeg.org/ffmpeg.git}"
# Jellyfin's list: the formats the 2D player already decodes through FFmpeg.
ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
# The library's minimum API level; never above the flavor's minSdk (32).
ANDROID_ABI=32

REBUILD=0
for arg in "$@"; do
    case "$arg" in
        --rebuild) REBUILD=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'build-ffmpeg-decoder: %s\n' "$*" >&2; exit 1; }

# --- Toolchain -------------------------------------------------------------

sdk_dir() {
    if [[ -n "${ANDROID_HOME:-}" ]]; then echo "$ANDROID_HOME"; return; fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "$ANDROID_SDK_ROOT"; return; fi
    if [[ -f "$ROOT/local.properties" ]]; then
        local dir
        dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1 | sed 's/\\:/:/g')"
        if [[ -n "$dir" ]]; then echo "$dir"; return; fi
    fi
    die "no Android SDK: set ANDROID_HOME or sdk.dir in local.properties"
}

SDK="$(sdk_dir)"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
CMAKE_BIN="$SDK/cmake/$CMAKE_VERSION/bin"
[[ -d "$NDK/toolchains/llvm/prebuilt" ]] || die "NDK $NDK_VERSION not found at $NDK; run: sdkmanager \"ndk;$NDK_VERSION\""
[[ -x "$CMAKE_BIN/cmake" ]] || die "CMake $CMAKE_VERSION not found at $CMAKE_BIN; run: sdkmanager \"cmake;$CMAKE_VERSION\""

case "$(uname -s)" in
    Linux) HOST_PLATFORM="linux-x86_64" ;;
    # NDK r26b ships one macOS prebuilt; Apple silicon runs it under Rosetta.
    Darwin) HOST_PLATFORM="darwin-x86_64" ;;
    *) die "unsupported host $(uname -s)" ;;
esac
[[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_PLATFORM" ]] || die "no $HOST_PLATFORM toolchain in $NDK"
STRIP="$NDK/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin/llvm-strip"

MEDIA3_VERSION="$(sed -n 's/^media3 *= *"\([^"]*\)".*/\1/p' "$ROOT/gradle/libs.versions.toml" | head -1)"
[[ -n "$MEDIA3_VERSION" ]] || die "no media3 version in gradle/libs.versions.toml"

echo "SDK:        $SDK"
echo "NDK:        $NDK ($HOST_PLATFORM)"
echo "CMake:      $CMAKE_BIN"
echo "Media3:     $MEDIA3_VERSION"
echo "FFmpeg:     $FFMPEG_BRANCH at $FFMPEG_COMMIT"
echo "Decoders:   ${ENABLED_DECODERS[*]}"
echo "ABIs:       ${ABIS[*]} (API $ANDROID_ABI)"
mkdir -p "$WORK"

# --- Media3 at the matching tag ---------------------------------------------

MEDIA3="$WORK/media3"
if [[ ! -d "$MEDIA3/.git" ]]; then
    log "Cloning Media3 $MEDIA3_VERSION (decoder_ffmpeg only)"
    git clone --quiet --depth 1 --branch "$MEDIA3_VERSION" --filter=blob:none --sparse "$MEDIA3_GIT_URL" "$MEDIA3"
    git -C "$MEDIA3" sparse-checkout set libraries/decoder_ffmpeg
else
    actual="$(git -C "$MEDIA3" describe --tags --exact-match 2>/dev/null || true)"
    [[ "$actual" == "$MEDIA3_VERSION" ]] || die "$MEDIA3 is at '$actual', not $MEDIA3_VERSION; delete it and rerun"
fi
MODULE="$MEDIA3/libraries/decoder_ffmpeg/src/main"
[[ -x "$MODULE/jni/build_ffmpeg.sh" ]] || die "no build_ffmpeg.sh in $MODULE/jni"

log "Checking the vendored decoder classes against Media3 $MEDIA3_VERSION"
for src in "$MODULE/java/androidx/media3/decoder/ffmpeg/"*.java; do
    name="$(basename "$src")"
    cmp -s "$src" "$VENDORED/$name" || die "$VENDORED/$name differs from Media3 $MEDIA3_VERSION; copy the tag's file over it"
done
for vendored in "$VENDORED"/*.java; do
    name="$(basename "$vendored")"
    [[ -f "$MODULE/java/androidx/media3/decoder/ffmpeg/$name" ]] || die "$vendored has no counterpart in Media3 $MEDIA3_VERSION"
done
echo "vendored classes match"

# --- FFmpeg at Jellyfin's pin ----------------------------------------------

FFMPEG="$WORK/ffmpeg"
if [[ ! -d "$FFMPEG/.git" ]]; then
    log "Cloning FFmpeg $FFMPEG_BRANCH"
    git clone --quiet --branch "$FFMPEG_BRANCH" --single-branch "$FFMPEG_GIT_URL" "$FFMPEG"
fi
if [[ "$(git -C "$FFMPEG" rev-parse HEAD)" != "$FFMPEG_COMMIT" ]]; then
    git -C "$FFMPEG" fetch --quiet origin "$FFMPEG_BRANCH"
    git -C "$FFMPEG" checkout --quiet "$FFMPEG_COMMIT"
fi
echo "FFmpeg $(cat "$FFMPEG/RELEASE") at $(git -C "$FFMPEG" rev-parse --short HEAD)"
ln -sfn "$FFMPEG" "$MODULE/jni/ffmpeg"

# --- FFmpeg's static libraries, through Media3's script unmodified -----------

ffmpeg_built() {
    local abi
    for abi in "${ABIS[@]}"; do
        [[ -f "$FFMPEG/android-libs/$abi/libavcodec.a" ]] || return 1
    done
    [[ -f "$FFMPEG/config.h" ]]
}

if [[ "$REBUILD" == 1 ]] || ! ffmpeg_built; then
    log "Building FFmpeg for ${ABIS[*]} with Media3's build_ffmpeg.sh"
    rm -rf "$FFMPEG/android-libs"
    ( cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK" "$HOST_PLATFORM" "$ANDROID_ABI" "${ENABLED_DECODERS[@]}" )
else
    echo "FFmpeg libraries exist; skipping (pass --rebuild to build again)"
fi
ffmpeg_built || die "build_ffmpeg.sh left no libraries"

# --- The licence check (D13) ------------------------------------------------

log "Checking FFmpeg's licence"
LICENSE_LINE="$(grep -E '^#define FFMPEG_LICENSE ' "$FFMPEG/config.h" || true)"
echo "${LICENSE_LINE:-no FFMPEG_LICENSE in config.h}"
[[ "$LICENSE_LINE" == *'"LGPL version 2.1 or later"'* ]] \
    || die "FFmpeg was not configured as LGPL-2.1 (no --enable-gpl, --enable-nonfree, or --enable-version3 may be set); not building"
for flag in CONFIG_GPL CONFIG_NONFREE CONFIG_VERSION3; do
    if grep -qE "^#define $flag 1" "$FFMPEG/config.h"; then die "$flag is on in config.h"; fi
done

# --- libffmpegJNI.so for each ABI -------------------------------------------

mkdir -p "$OUT"
for abi in "${ABIS[@]}"; do
    log "Building libffmpegJNI.so for $abi"
    build_dir="$WORK/jni-build/$abi"
    mkdir -p "$build_dir"
    "$CMAKE_BIN/cmake" -S "$MODULE/jni" -B "$build_dir" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$ANDROID_ABI" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release > "$build_dir.configure.log"
    "$CMAKE_BIN/cmake" --build "$build_dir" > "$build_dir.build.log"
    mkdir -p "$OUT/$abi"
    "$STRIP" --strip-unneeded -o "$OUT/$abi/libffmpegJNI.so" "$build_dir/libffmpegJNI.so"
done

# --- Summary ----------------------------------------------------------------

{
    echo "libffmpegJNI.so built by tools/horizon/build-ffmpeg-decoder.sh (HORIZON.md G.1)"
    echo "Media3 $MEDIA3_VERSION ($(git -C "$MEDIA3" rev-parse HEAD))"
    echo "FFmpeg $(cat "$FFMPEG/RELEASE") $FFMPEG_BRANCH ($FFMPEG_COMMIT), $LICENSE_LINE"
    echo "NDK $NDK_VERSION, CMake $CMAKE_VERSION, API $ANDROID_ABI"
    echo "Decoders: ${ENABLED_DECODERS[*]}"
} > "$OUT/BUILD-INFO.txt"

log "Installed"
for abi in "${ABIS[@]}"; do
    file="$OUT/$abi/libffmpegJNI.so"
    if command -v shasum > /dev/null; then sum="$(shasum -a 256 "$file" | cut -c1-16)"; else sum="$(sha256sum "$file" | cut -c1-16)"; fi
    printf '  %-12s %9d bytes  sha256 %s…\n' "$abi" "$(wc -c < "$file")" "$sum"
done
echo "FFmpeg licence: ${LICENSE_LINE#\#define FFMPEG_LICENSE }"
