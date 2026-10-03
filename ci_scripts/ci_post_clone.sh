#!/bin/sh

# Xcode Cloud post-clone step.
#
# Local builds are NOT affected by this script — developers keep using their own
# gitignored .secret/Secrets.xcconfig (see Shared/Example.xcconfig for setup).
#
# In Xcode Cloud, .secret/ doesn't exist (it's gitignored), so we regenerate
# .secret/Secrets.xcconfig from environment variables configured on the
# workflow. Mark TMDB_READ_ACCESS_TOKEN (and optionally TMDB_API_KEY,
# WYZIE_API_KEY, GOOGLE_DRIVE_CLIENT_ID, ONEDRIVE_CLIENT_ID, and
# DROPBOX_APP_KEY) as *Secret* env vars in the Xcode Cloud workflow so they're
# encrypted and masked in build logs.
#
# Never echo the token values here — that would leak them into the build log.

set -eu

if [ -z "${CI_PRIMARY_REPOSITORY_PATH:-}" ]; then
  echo "error: CI_PRIMARY_REPOSITORY_PATH is not set." >&2
  exit 1
fi

if [ ! -d "$CI_PRIMARY_REPOSITORY_PATH/Edendale.xcodeproj" ]; then
  echo "error: Edendale.xcodeproj not found in the primary repository." >&2
  exit 1
fi

SECRETS_DIRECTORY="$CI_PRIMARY_REPOSITORY_PATH/.secret"
SECRETS_FILE="$SECRETS_DIRECTORY/Secrets.xcconfig"

# Keep the generated credential directory and file private in Xcode Cloud's
# temporary checkout.
umask 077
mkdir -p "$SECRETS_DIRECTORY"

{
  printf 'TMDB_READ_ACCESS_TOKEN = %s\n' "${TMDB_READ_ACCESS_TOKEN:-}"
  printf 'TMDB_API_KEY = %s\n' "${TMDB_API_KEY:-}"
  printf 'WYZIE_API_KEY = %s\n' "${WYZIE_API_KEY:-}"
  printf 'GOOGLE_DRIVE_CLIENT_ID = %s\n' "${GOOGLE_DRIVE_CLIENT_ID:-}"
  printf 'ONEDRIVE_CLIENT_ID = %s\n' "${ONEDRIVE_CLIENT_ID:-}"
  printf 'DROPBOX_APP_KEY = %s\n' "${DROPBOX_APP_KEY:-}"
} > "$SECRETS_FILE"

echo "Generated .secret/Secrets.xcconfig for Xcode Cloud build."

# FFmpeg is generated, not committed. Prepare it before Xcode resolves the local
# XCFramework reference. Keep secrets out of the dependency compiler environment.
unset TMDB_READ_ACCESS_TOKEN TMDB_API_KEY WYZIE_API_KEY \
  GOOGLE_DRIVE_CLIENT_ID ONEDRIVE_CLIENT_ID DROPBOX_APP_KEY
case "${CI_PRODUCT_PLATFORM:-}" in
  iOS|ios) FFMPEG_PLATFORM=ios ;;
  macOS|macos) FFMPEG_PLATFORM=macos ;;
  tvOS|tvos) FFMPEG_PLATFORM=tvos ;;
  visionOS|xrOS|visionos|xros) FFMPEG_PLATFORM=visionos ;;
  *)
    echo "error: Unsupported or missing CI_PRODUCT_PLATFORM for FFmpeg: '${CI_PRODUCT_PLATFORM:-}'." >&2
    exit 1
    ;;
esac

/bin/bash "$CI_PRIMARY_REPOSITORY_PATH/Vendor/FFmpeg/build-ffmpeg.sh" \
  --platform "$FFMPEG_PLATFORM"
