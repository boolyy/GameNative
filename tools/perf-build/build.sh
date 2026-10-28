#!/usr/bin/env bash
# Runs Gradle (or any command) for this fork inside the perf-build container.
#
#   tools/perf-build/build.sh [options] [--] <gradle tasks or command...>
#
# Options:
#   --shell            drop into a shell in the container instead of running Gradle
#   --raw              run the arguments as a plain command (not prefixed with ./gradlew)
#   --rebuild-image    force a rebuild of the Docker image
#   --out DIR          after a successful build copy app/build/outputs/apk/**/*.apk to DIR
#                      and write DIR/SHA256SUMS
#   --workers N        Gradle --max-workers (default 8)
#   --heap SIZE        Gradle daemon heap (default 6g); the Kotlin daemon gets 3g
#
# Environment:
#   PERF_KEYSTORE_PROPS  host path to keystore.properties (Phase 2). Mounted read-only at
#                        /keys and passed into Gradle as PERF_KEYSTORE_PROPS=/keys/<name>.
#
# Everything heavy (SDK, NDK, Gradle caches) lives under ~/.cache/gamenative-perf, which the
# nightly backup excludes. Nothing is installed on the host. The container runs as the
# calling user so no root-owned files land in the checkout.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cache="${GAMENATIVE_PERF_CACHE:-$HOME/.cache/gamenative-perf}"
image="${GAMENATIVE_PERF_IMAGE:-gamenative-perf-build:latest}"

shell=0 raw=0 rebuild=0 out="" workers=8 heap=6g
while [ $# -gt 0 ]; do
  case "$1" in
    --shell) shell=1; shift ;;
    --raw) raw=1; shift ;;
    --rebuild-image) rebuild=1; shift ;;
    --out) out="$2"; shift 2 ;;
    --workers) workers="$2"; shift 2 ;;
    --heap) heap="$2"; shift 2 ;;
    --) shift; break ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) break ;;
  esac
done

mkdir -p "$cache/android-sdk" "$cache/gradle" "$cache/home"

if [ "$rebuild" = 1 ] || ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "perf-build: building image $image" >&2
  docker build -t "$image" "$here"
fi

# Gradle wrapper validation, the same check upstream CI does (gradle/actions/wrapper-validation):
# the jar's SHA-256 must be one Gradle published for the wrapper version in use.
"$here/validate-wrapper.sh" "$repo"

args=(
  run --rm
  --init
  -u "$(id -u):$(id -g)"
  --memory "${GAMENATIVE_PERF_MEMORY:-14g}"
  -v "$repo:/src"
  -v "$cache/android-sdk:/sdk"
  -v "$cache/gradle:/gradle"
  -v "$cache/home:/home/builder"
  -e "GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx${heap} -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
  -e "KOTLIN_DAEMON_JVM_OPTIONS=-Xmx3g"
)
if [ -t 0 ] && [ -t 1 ]; then args+=(-it); fi

if [ -n "${PERF_KEYSTORE_PROPS:-}" ]; then
  keydir="$(cd "$(dirname "$PERF_KEYSTORE_PROPS")" && pwd)"
  args+=(-v "$keydir:/keys:ro" -e "PERF_KEYSTORE_PROPS=/keys/$(basename "$PERF_KEYSTORE_PROPS")")
fi

if [ "$shell" = 1 ]; then
  exec docker "${args[@]}" -it "$image" bash
fi

if [ "$raw" = 1 ]; then
  docker "${args[@]}" "$image" "$@"
  exit $?
fi

if [ $# -eq 0 ]; then
  echo "usage: $0 [options] <gradle tasks...>" >&2
  exit 2
fi

start=$(date +%s)
docker "${args[@]}" "$image" ./gradlew --no-daemon --max-workers="$workers" \
  -Pkotlin.daemon.jvmargs=-Xmx3g "$@"
status=$?
end=$(date +%s)
echo "perf-build: gradle exited $status after $((end - start)) s" >&2
[ $status -eq 0 ] || exit $status

if [ -n "$out" ]; then
  mkdir -p "$out"
  found=0
  while IFS= read -r -d '' apk; do
    cp -f "$apk" "$out/"
    found=1
  done < <(find "$repo/app/build/outputs/apk" -name '*.apk' -print0 2>/dev/null)
  if [ "$found" = 1 ]; then
    (cd "$out" && sha256sum *.apk > SHA256SUMS)
    echo "perf-build: artifacts in $out" >&2
    cat "$out/SHA256SUMS" >&2
  else
    echo "perf-build: no APKs found under app/build/outputs/apk" >&2
    exit 1
  fi
fi
