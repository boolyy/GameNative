#!/usr/bin/env bash
# Checks a built perf APK inside the build container: package name, label, native libs and the
# signing certificate. Prints the badging summary. Exit 1 on any mismatch.
#
#   tools/perf-build/verify-apk.sh <apk> [expected-sha256-fingerprint]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
apk="$(readlink -f "$1")"
expected_fp="${2:-${PERF_CERT_SHA256:-}}"
expected_pkg="${PERF_PACKAGE:-app.gamenative.perf}"
expected_label="${PERF_LABEL:-GameNative Perf}"
apk_dir="$(dirname "$apk")"
apk_name="$(basename "$apk")"

# Runs aapt2 / apksigner from the mounted SDK; the APK's directory is mounted read-only at /apk.
run() {
  docker run --rm -u "$(id -u):$(id -g)" \
    -v "${GAMENATIVE_PERF_CACHE:-$HOME/.cache/gamenative-perf}/android-sdk:/sdk:ro" \
    -v "$apk_dir:/apk:ro" -e HOME=/tmp \
    --entrypoint bash "${GAMENATIVE_PERF_IMAGE:-gamenative-perf-build:latest}" -c "$1"
}

badging="$(run "/sdk/build-tools/34.0.0/aapt2 dump badging '/apk/$apk_name'")"
pkg="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
label="$(printf '%s\n' "$badging" | sed -n "s/^application-label:'\([^']*\)'.*/\1/p" | head -1)"
version="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")"
code="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p")"
abis="$(printf '%s\n' "$badging" | sed -n "s/^native-code: //p")"
echo "package=$pkg versionName=$version versionCode=$code label='$label' native-code=$abis"

status=0
[ "$pkg" = "$expected_pkg" ] || { echo "FAIL: package $pkg != $expected_pkg"; status=1; }
[ "$label" = "$expected_label" ] || { echo "FAIL: label '$label' != '$expected_label'"; status=1; }
printf '%s' "$abis" | grep -q "arm64-v8a" || { echo "FAIL: no arm64-v8a native code"; status=1; }

certs="$(run "/sdk/build-tools/34.0.0/apksigner verify --print-certs '/apk/$apk_name'")"
echo "$certs" | grep -E 'Verified using|certificate SHA-256' || true
fp="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
if [ -n "$expected_fp" ]; then
  norm_expected="$(printf '%s' "$expected_fp" | tr -d ':' | tr 'A-F' 'a-f')"
  [ "$fp" = "$norm_expected" ] || { echo "FAIL: certificate $fp != expected $norm_expected"; status=1; }
fi
size=$(stat -c %s "$apk")
echo "size=$((size / 1048576)) MB"
[ "$size" -gt 300000000 ] || { echo "FAIL: APK suspiciously small ($size bytes)"; status=1; }
exit $status
