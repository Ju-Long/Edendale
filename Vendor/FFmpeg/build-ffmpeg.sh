#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FFMPEG_VERSION="7.1.1"
FFMPEG_SHA256="733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1"
SRC_DIR="${SCRIPT_DIR}/ffmpeg-${FFMPEG_VERSION}"
BUILD_ROOT="${SCRIPT_DIR}/build"
FRAMEWORKS_DIR="${SCRIPT_DIR}/frameworks"
XCFRAMEWORK_DIR="${SCRIPT_DIR}/FFmpeg.xcframework"
NCPU=$(sysctl -n hw.ncpu || echo 4)
PLATFORM="all"
CLEAN=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --platform)
            PLATFORM="${2:?--platform requires all, ios, macos, tvos, or visionos}"
            shift 2
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        *)
            echo "error: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

case "${PLATFORM}" in
    all|ios|macos|tvos|visionos) ;;
    *) echo "error: Unsupported FFmpeg platform: ${PLATFORM}" >&2; exit 1 ;;
esac

# 1. Download FFmpeg source if not present
if [ ! -d "${SRC_DIR}" ]; then
    echo "==> Downloading FFmpeg ${FFMPEG_VERSION}..."
    cd "${SCRIPT_DIR}"
    curl --fail --location --retry 3 --output "ffmpeg-${FFMPEG_VERSION}.tar.xz" \
        "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    printf '%s  %s\n' "${FFMPEG_SHA256}" "ffmpeg-${FFMPEG_VERSION}.tar.xz" | shasum -a 256 -c -
    tar -xf "ffmpeg-${FFMPEG_VERSION}.tar.xz"
    rm "ffmpeg-${FFMPEG_VERSION}.tar.xz"
fi

# Apply visionOS patch to videotoolbox.c if needed
if grep -q "kCVPixelBufferOpenGLESCompatibilityKey" "${SRC_DIR}/libavcodec/videotoolbox.c" && ! grep -q "TARGET_OS_VISION" "${SRC_DIR}/libavcodec/videotoolbox.c"; then
    echo "==> Patching videotoolbox.c for visionOS compatibility..."
    sed -i '' 's/#if TARGET_OS_IPHONE/#if defined(TARGET_OS_VISION) \&\& TARGET_OS_VISION\
    CFDictionarySetValue(buffer_attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue);\
#elif TARGET_OS_IPHONE/' "${SRC_DIR}/libavcodec/videotoolbox.c"
fi

COMMON_CONFIG=(
    --enable-static
    --disable-shared
    --enable-pic
    --disable-programs
    --disable-doc
    --disable-everything
    --enable-videotoolbox
    --enable-hwaccel=h264_videotoolbox
    --enable-hwaccel=hevc_videotoolbox
    --enable-hwaccel=vp9_videotoolbox
    --enable-decoder=h264,hevc,vp8,vp9,av1,mpeg2video,mpeg4,aac,mp3,flac,opus,vorbis,ac3,eac3,dca,truehd,ass,srt,subrip,webvtt,pgssub,dvdsub
    '--enable-decoder=pcm_*'
    --enable-demuxer=matroska,avi,mpegts,flv,ogg,mov,mp3,wav,flac,ass,srt,concat
    --enable-parser=h264,hevc,vp8,vp9,av1,mpegvideo,mpeg4video,aac,mpegaudio,flac,opus,vorbis,ac3,dca
    --enable-protocol=file,http,https,tcp,udp,concat
    --enable-audiotoolbox
)

build_slice() {
    local platform_name="$1"
    local arch="$2"
    local sdk_name="$3"
    local min_flag="$4"
    local disable_asm="${5:-false}"

    local sdk_path
    sdk_path="$(xcrun --sdk "${sdk_name}" --show-sdk-path)"
    local cc_path
    cc_path="$(xcrun --sdk "${sdk_name}" --find clang)"

    local slice_dir="${BUILD_ROOT}/${platform_name}-${arch}"
    local prefix="${slice_dir}/install"
    local cflags="-fembed-bitcode=off -fPIC -fno-common -arch ${arch} ${min_flag}"
    local ldflags="-arch ${arch} ${min_flag}"
    local signature
    signature=$(printf '%s\n' "${FFMPEG_VERSION}" "${COMMON_CONFIG[@]}" \
        "${cflags}" "${ldflags}" "${sdk_path}" "${disable_asm}" | shasum -a 256 | awk '{ print $1 }')

    if [ -f "${prefix}/lib/libavcodec.a" ] && \
       [ -f "${prefix}/.edendale-build-signature" ] && \
       [ "$(cat "${prefix}/.edendale-build-signature")" = "${signature}" ]; then
        echo "==> Slice ${platform_name}-${arch} already built. Skipping."
        return 0
    fi

    echo "==> Building ${platform_name} (${arch})..."
    # Changed compiler flags do not reliably invalidate old FFmpeg objects.
    # Only reuse a completed slice built with the isolation prerequisites.
    rm -rf "${slice_dir}"
    mkdir -p "${slice_dir}"
    cd "${slice_dir}"

    local extra_args=()

    if [ "${disable_asm}" = "true" ]; then
        extra_args+=(--disable-x86asm --disable-inline-asm)
    fi

    local target_arch="${arch}"
    if [ "${arch}" = "arm64" ]; then
        target_arch="aarch64"
    fi

    if [ "${#extra_args[@]}" -gt 0 ]; then
        "${SRC_DIR}/configure" \
            --prefix="${prefix}" \
            --enable-cross-compile \
            --target-os=darwin \
            --arch="${target_arch}" \
            --cc="${cc_path}" \
            --sysroot="${sdk_path}" \
            --extra-cflags="${cflags}" \
            --extra-ldflags="${ldflags}" \
            "${COMMON_CONFIG[@]}" \
            "${extra_args[@]}"
    else
        "${SRC_DIR}/configure" \
            --prefix="${prefix}" \
            --enable-cross-compile \
            --target-os=darwin \
            --arch="${target_arch}" \
            --cc="${cc_path}" \
            --sysroot="${sdk_path}" \
            --extra-cflags="${cflags}" \
            --extra-ldflags="${ldflags}" \
            "${COMMON_CONFIG[@]}"
    fi

    make -j"${NCPU}"
    make install
    printf '%s\n' "${signature}" > "${prefix}/.edendale-build-signature"
}

if [ "${CLEAN}" = true ]; then
    echo "==> Cleaning build directory..."
    rm -rf "${BUILD_ROOT}" "${FRAMEWORKS_DIR}"
fi
mkdir -p "${BUILD_ROOT}" "${FRAMEWORKS_DIR}"

# Local builds default to every platform. Cloud only needs the current action's
# platform, including its simulator variant for build/test actions.
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = macos ]; then
    build_slice "macos" "arm64" "macosx" "-mmacosx-version-min=15.0" false
    build_slice "macos" "x86_64" "macosx" "-mmacosx-version-min=15.0" true
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = ios ]; then
    build_slice "ios" "arm64" "iphoneos" "-miphoneos-version-min=18.0" false
    build_slice "ios-sim" "arm64" "iphonesimulator" "-target arm64-apple-ios18.0-simulator" false
    build_slice "ios-sim" "x86_64" "iphonesimulator" "-target x86_64-apple-ios18.0-simulator" true
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = tvos ]; then
    build_slice "tvos" "arm64" "appletvos" "-mappletvos-version-min=18.0" false
    build_slice "tvos-sim" "arm64" "appletvsimulator" "-target arm64-apple-tvos18.0-simulator" false
    build_slice "tvos-sim" "x86_64" "appletvsimulator" "-target x86_64-apple-tvos18.0-simulator" true
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = visionos ]; then
    build_slice "xros" "arm64" "xros" "-target arm64-apple-xros2.0" false
    build_slice "xrsimulator" "arm64" "xrsimulator" "-target arm64-apple-xros2.0-simulator" false
fi

# Assemble Framework bundles for each platform variant
create_framework() {
    local variant_name="$1"
    shift
    local slices=("$@")

    local fmwk_dir="${FRAMEWORKS_DIR}/${variant_name}/FFmpeg.framework"
    rm -rf "${fmwk_dir}"
    mkdir -p "${fmwk_dir}/Headers" "${fmwk_dir}/Modules"

    echo "==> Creating Framework for ${variant_name} from: ${slices[*]}..."

    # Merge individual static libraries per slice first
    local merged_libs=()
    for slice in "${slices[@]}"; do
        local prefix="${BUILD_ROOT}/${slice}/install"
        local slice_lib="${BUILD_ROOT}/${slice}/libFFmpeg_slice.a"
        libtool -static -o "${slice_lib}" \
            "${prefix}/lib/libavcodec.a" \
            "${prefix}/lib/libavformat.a" \
            "${prefix}/lib/libavutil.a" \
            "${prefix}/lib/libswresample.a" \
            "${prefix}/lib/libswscale.a"
        local arch="${slice##*-}"
        local sdk platform min_version
        case "${slice%-*}" in
            macos) sdk=macosx; platform=macos; min_version=15.0 ;;
            ios) sdk=iphoneos; platform=ios; min_version=18.0 ;;
            ios-sim) sdk=iphonesimulator; platform=ios-simulator; min_version=18.0 ;;
            tvos) sdk=appletvos; platform=tvos; min_version=18.0 ;;
            tvos-sim) sdk=appletvsimulator; platform=tvos-simulator; min_version=18.0 ;;
            xros) sdk=xros; platform=visionos; min_version=2.0 ;;
            xrsimulator) sdk=xrsimulator; platform=visionos-simulator; min_version=2.0 ;;
            *) echo "error: Unknown FFmpeg slice: ${slice}" >&2; exit 1 ;;
        esac
        local isolated_lib="${BUILD_ROOT}/${slice}/libEdendaleFFmpeg.a"
        local namespace_header="${BUILD_ROOT}/${slice}/EdendaleFFmpegSymbols.h"
        bash "${SCRIPT_DIR}/isolate-symbols.sh" "${slice_lib}" "${isolated_lib}" \
            "${namespace_header}" "${arch}" "${platform}" "${min_version}" \
            "$(xcrun --sdk "${sdk}" --show-sdk-version)"
        if [ -f "${fmwk_dir}/Headers/EdendaleFFmpegSymbols.h" ]; then
            cmp "${fmwk_dir}/Headers/EdendaleFFmpegSymbols.h" "${namespace_header}"
        else
            cp "${namespace_header}" "${fmwk_dir}/Headers/EdendaleFFmpegSymbols.h"
        fi
        merged_libs+=("${isolated_lib}")
    done

    # If multiple slices (e.g. arm64 + x86_64), lipo them into one universal library
    if [ "${#merged_libs[@]}" -gt 1 ]; then
        lipo -create "${merged_libs[@]}" -output "${fmwk_dir}/FFmpeg"
    else
        cp "${merged_libs[0]}" "${fmwk_dir}/FFmpeg"
    fi

    # Copy headers from the first slice
    local ref_prefix="${BUILD_ROOT}/${slices[0]}/install"
    cp -R "${ref_prefix}/include/"* "${fmwk_dir}/Headers/"

    # Create Umbrella Header
    cat > "${fmwk_dir}/Headers/FFmpeg.h" << 'EOF'
#ifndef FFmpeg_h
#define FFmpeg_h

#include "EdendaleFFmpegSymbols.h"
#include "libavcodec/avcodec.h"
#include "libavcodec/videotoolbox.h"
#include "libavformat/avformat.h"
#include "libavformat/avio.h"
#include "libavutil/avutil.h"
#include "libavutil/error.h"
#include "libavutil/opt.h"
#include "libavutil/channel_layout.h"
#include "libavutil/imgutils.h"
#include "libavutil/hwcontext.h"
#include "libavutil/hwcontext_videotoolbox.h"
#include "libswresample/swresample.h"
#include "libswscale/swscale.h"

#endif /* FFmpeg_h */
EOF

    # Internal symlinks so subdirectories can find sibling headers
    (
        cd "${fmwk_dir}/Headers"
        ln -sf ../libavutil libavcodec/libavutil
        ln -sf ../libavcodec libavformat/libavcodec
        ln -sf ../libavutil libavformat/libavutil
        ln -sf ../libavutil libswscale/libavutil
        ln -sf ../libavutil libswresample/libavutil
    )

    # Create Module Map
    cat > "${fmwk_dir}/Modules/module.modulemap" << 'EOF'
framework module FFmpeg [system] {
    umbrella header "FFmpeg.h"
    export *
    link "z"
    link "bz2"
    link "iconv"
    link framework "AudioToolbox"
    link framework "CoreMedia"
    link framework "CoreVideo"
    link framework "VideoToolbox"
}
EOF

    # Create Info.plist
    cat > "${fmwk_dir}/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>FFmpeg</string>
    <key>CFBundleIdentifier</key>
    <string>org.ffmpeg.FFmpeg</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>FFmpeg</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>${FFMPEG_VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${FFMPEG_VERSION}</string>
</dict>
</plist>
EOF
}

FRAMEWORK_ARGS=()
add_framework() {
    create_framework "$@"
    FRAMEWORK_ARGS+=(-framework "${FRAMEWORKS_DIR}/$1/FFmpeg.framework")
}

if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = macos ]; then
    add_framework "macos" "macos-arm64" "macos-x86_64"
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = ios ]; then
    add_framework "ios" "ios-arm64"
    add_framework "ios-simulator" "ios-sim-arm64" "ios-sim-x86_64"
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = tvos ]; then
    add_framework "tvos" "tvos-arm64"
    add_framework "tvos-simulator" "tvos-sim-arm64" "tvos-sim-x86_64"
fi
if [ "${PLATFORM}" = all ] || [ "${PLATFORM}" = visionos ]; then
    add_framework "xros" "xros-arm64"
    add_framework "xrsimulator" "xrsimulator-arm64"
fi

# Create final XCFramework
echo "==> Assembling FFmpeg.xcframework..."
rm -rf "${XCFRAMEWORK_DIR}"
xcodebuild -create-xcframework \
    "${FRAMEWORK_ARGS[@]}" \
    -output "${XCFRAMEWORK_DIR}"

echo "==> Successfully created ${XCFRAMEWORK_DIR}!"
