#!/usr/bin/env bash
# Container entrypoint: makes sure the SDK packages the build needs are present in the
# bind-mounted /sdk, then runs the given command. Runs as the host user (no root).
set -euo pipefail

# Versions the upstream build asks for (app/build.gradle.kts + the release workflow).
PACKAGES=(
  "platforms;android-36"
  "build-tools;34.0.0"
  "build-tools;35.0.0"
  "platform-tools"
  "ndk;27.3.13750724"
)

mkdir -p "$HOME" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME" "$ANDROID_HOME"

sdk_setup() {
  local missing=()
  for p in "${PACKAGES[@]}"; do
    local dir="${ANDROID_HOME}/${p//;//}"
    [ -d "$dir" ] || missing+=("$p")
  done
  if [ ${#missing[@]} -eq 0 ]; then
    return 0
  fi
  echo "perf-build: installing SDK packages: ${missing[*]}" >&2
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  sdkmanager --sdk_root="$ANDROID_HOME" "${missing[@]}"
}

case "${1:-}" in
  sdk-setup) sdk_setup; exit 0 ;;
  "") exec bash ;;
esac

sdk_setup
exec "$@"
