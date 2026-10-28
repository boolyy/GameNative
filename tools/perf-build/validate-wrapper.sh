#!/usr/bin/env bash
# Checks gradle/wrapper/gradle-wrapper.jar against the checksum Gradle publishes for the
# wrapper version named in gradle-wrapper.properties. Same idea as the
# gradle/actions/wrapper-validation step in upstream CI. The result is cached per jar hash.
set -euo pipefail
repo="${1:-.}"
jar="$repo/gradle/wrapper/gradle-wrapper.jar"
props="$repo/gradle/wrapper/gradle-wrapper.properties"
cache="${GAMENATIVE_PERF_CACHE:-$HOME/.cache/gamenative-perf}/wrapper-ok"
mkdir -p "$cache"

have="$(sha256sum "$jar" | cut -d' ' -f1)"
if [ -f "$cache/$have" ]; then
  exit 0
fi

version="$(sed -n 's#.*distributions/gradle-\([0-9.]*\)-.*#\1#p' "$props")"
[ -n "$version" ] || { echo "validate-wrapper: cannot read Gradle version from $props" >&2; exit 1; }

url="$(curl -fsSL https://services.gradle.org/versions/all | python3 -I -c '
import json, sys
want = sys.argv[1]
for e in json.load(sys.stdin):
    if e.get("version") == want and e.get("wrapperChecksumUrl"):
        print(e["wrapperChecksumUrl"]); break
' "$version")"
[ -n "$url" ] || { echo "validate-wrapper: no published wrapper checksum for Gradle $version" >&2; exit 1; }

expected="$(curl -fsSL "$url" | tr -d '[:space:]')"
if [ "$have" != "$expected" ]; then
  echo "validate-wrapper: gradle-wrapper.jar sha256 $have does not match published $expected for Gradle $version" >&2
  exit 1
fi
touch "$cache/$have"
echo "validate-wrapper: gradle-wrapper.jar ok (Gradle $version)" >&2
