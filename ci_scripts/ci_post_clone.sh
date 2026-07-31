#!/bin/sh

# Xcode Cloud post-clone step.
#
# Local builds are NOT affected by this script — developers keep using their own
# gitignored Shared/Secrets.xcconfig (see Shared/Example.xcconfig for setup).
#
# In Xcode Cloud, Secrets.xcconfig doesn't exist (it's gitignored), so we
# regenerate it from environment variables configured on the workflow. Mark
# TMDB_READ_ACCESS_TOKEN (and optionally TMDB_API_KEY and WYZIE_API_KEY) as
# *Secret* env vars in the Xcode Cloud workflow so they're encrypted and
# masked in build logs.
#
# Never echo the token values here — that would leak them into the build log.

set -eu

if [ -z "${CI_PRIMARY_REPOSITORY_PATH:-}" ]; then
  echo "error: CI_PRIMARY_REPOSITORY_PATH is not set." >&2
  exit 1
fi

SECRETS_DIRECTORY="$CI_PRIMARY_REPOSITORY_PATH/Shared"
SECRETS_FILE="$SECRETS_DIRECTORY/Secrets.xcconfig"

if [ ! -d "$SECRETS_DIRECTORY" ]; then
  echo "error: Shared directory not found in the primary repository." >&2
  exit 1
fi

# Keep the generated credential file private in Xcode Cloud's temporary checkout.
umask 077

{
  printf 'TMDB_READ_ACCESS_TOKEN = %s\n' "${TMDB_READ_ACCESS_TOKEN:-}"
  printf 'TMDB_API_KEY = %s\n' "${TMDB_API_KEY:-}"
  printf 'WYZIE_API_KEY = %s\n' "${WYZIE_API_KEY:-}"
} > "$SECRETS_FILE"

echo "Generated Secrets.xcconfig for Xcode Cloud build."
