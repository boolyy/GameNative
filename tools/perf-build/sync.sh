#!/usr/bin/env bash
# Tracks upstream stable releases: rebases the perf patch onto each new tag, builds, tests,
# verifies, and publishes a GitHub release on the fork. Run daily by the systemd user timer in
# tools/perf-build/systemd/ (see FORK.md). Never installs anything on a device.
#
#   tools/perf-build/sync.sh [--force-tag <tag>] [--from-tag <tag>] [--dry-run] [--no-publish]
#
#   --force-tag TAG   treat TAG as the latest stable release (replay / testing)
#   --from-tag TAG    treat TAG as the last built release instead of state/last-tag
#   --dry-run         rebase + build + test + verify, but do not push, tag, release or update state
#   --no-publish      everything except the GitHub release (push and tag still happen)
#
# Exit codes: 0 nothing to do or success; 2 rebase conflict (alert sent); 3 build/test/verify
# failed (alert sent); 4 publish failed (alert sent).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
state_dir="${GAMENATIVE_PERF_STATE:-$HOME/gamenative-perf}"
keys="$state_dir/keys/keystore.properties"
out_root="$state_dir/out"
logs="$state_dir/logs"
mkdir -p "$state_dir/state" "$out_root" "$logs"
last_tag_file="$state_dir/state/last-tag"
upstream_repo="utkarshdalal/GameNative"
fork_repo="${GAMENATIVE_PERF_FORK:-boolyy/GameNative}"
ntfy_url="${NTFY_URL:-http://127.0.0.1:2586/tower-alerts}"
keep_builds=3

force_tag="" from_tag="" dry_run=0 publish=1
while [ $# -gt 0 ]; do
  case "$1" in
    --force-tag) force_tag="$2"; shift 2 ;;
    --from-tag) from_tag="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    --no-publish) publish=0; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

log() { echo "sync: $(date '+%F %T') $*" >&2; }

notify() { # priority title message
  local prio="$1" title="$2" msg="$3"
  curl -fsS -m 10 -H "Title: $title" -H "Priority: $prio" -H "Tags: gamenative" -d "$msg" "$ntfy_url" >/dev/null 2>&1 \
    || log "ntfy failed: $title"
}

cd "$repo"

# 1. Latest stable upstream release (prereleases are skipped by /releases/latest).
new_tag="${force_tag:-$(gh api "repos/$upstream_repo/releases/latest" --jq .tag_name)}"
old_tag="${from_tag:-$(cat "$last_tag_file" 2>/dev/null || true)}"
if [ -z "$old_tag" ]; then
  log "no state/last-tag; set it to the tag the current perf branch is based on"
  exit 2
fi
if [ "$new_tag" = "$old_tag" ]; then
  log "up to date ($new_tag)"
  exit 0
fi
log "upstream $old_tag -> $new_tag"

if [ -n "$(git status --porcelain)" ]; then
  log "working tree not clean, refusing to rebase"
  notify 4 "GameNative $new_tag: sync skipped" "The fork checkout has uncommitted changes; clean it and re-run sync.sh."
  exit 2
fi

# 2. Rebase the patch onto the new tag.
git fetch --quiet upstream --tags
git fetch --quiet origin
git rev-parse -q --verify "refs/tags/$new_tag" >/dev/null || { log "tag $new_tag not found after fetch"; exit 2; }
old_branch="perf/$old_tag"
new_branch="perf/$new_tag"
git rev-parse -q --verify "refs/heads/$old_branch" >/dev/null || git branch "$old_branch" "origin/$old_branch"
if git rev-parse -q --verify "refs/heads/$new_branch" >/dev/null; then
  log "branch $new_branch already exists; building it as is"
  git checkout -q "$new_branch"
else
  git checkout -q -b "$new_branch" "$old_branch"
  if ! git rebase --quiet --onto "$new_tag" "$old_tag" "$new_branch" >"$logs/rebase-$new_tag.log" 2>&1; then
    git rebase --abort || true
    git checkout -q "$old_branch"
    git branch -D "$new_branch" >/dev/null 2>&1 || true
    log "rebase conflict; see $logs/rebase-$new_tag.log"
    notify 4 "GameNative $new_tag: perf patch needs a manual rebase" \
      "git rebase --onto $new_tag $old_tag perf/$old_tag conflicted. Log: $logs/rebase-$new_tag.log. Nothing was pushed."
    exit 2
  fi
fi
log "rebased onto $new_tag: $(git rev-parse --short HEAD)"

# Build number: first fork build of this upstream tag is .1, bumped if a release already exists.
n=1
while gh release view "$new_tag-perf.$n" --repo "$fork_repo" >/dev/null 2>&1; do n=$((n + 1)); done
version="$new_tag-perf.$n"
out="$out_root/$version"
build_log="$logs/build-$version.log"

# 3. Build and test.
log "building $version"
rm -rf "$out"
if ! PERF_KEYSTORE_PROPS="$keys" PERF_BUILD_NUMBER="$n" \
     "$here/build.sh" --out "$out" :app:assembleLegacyPerf >"$build_log" 2>&1; then
  log "build failed; see $build_log"
  notify 4 "GameNative $version: build failed" "assembleLegacyPerf failed. Log: $build_log"
  exit 3
fi
if ! PERF_KEYSTORE_PROPS="$keys" PERF_BUILD_NUMBER="$n" \
     "$here/build.sh" :app:testLegacyDebugUnitTest >>"$build_log" 2>&1; then
  log "unit tests failed; see $build_log"
  notify 4 "GameNative $version: unit tests failed" "testLegacyDebugUnitTest failed. Log: $build_log"
  exit 3
fi

# 4. Verify the APK (package, label, ABI, signature) and keep the last builds.
apk="$(ls "$out"/*.apk | head -1)"
if ! "$here/verify-apk.sh" "$apk" >>"$build_log" 2>&1; then
  log "APK verification failed; see $build_log"
  notify 4 "GameNative $version: APK verification failed" "verify-apk.sh rejected $apk. Log: $build_log"
  exit 3
fi
final_apk="$out/gamenative-perf-$version.apk"
mv -f "$apk" "$final_apk"
(cd "$out" && sha256sum *.apk > SHA256SUMS)
ls -1dt "$out_root"/*/ | tail -n +$((keep_builds + 1)) | xargs -r rm -rf

if [ "$dry_run" = 1 ]; then
  log "dry run: built and verified $version in $out; not pushing"
  exit 0
fi

# 5. Push the branch and tag, publish the release.
git tag -a "$version" -m "GameNative Perf $version (upstream $new_tag)"
git push -u origin "$new_branch"
git push origin "$version"
if [ "$publish" = 1 ]; then
  notes="Fork build of upstream [$new_tag](https://github.com/$upstream_repo/releases/tag/$new_tag) with FrameBoost (see FORK.md).
Package \`app.gamenative.perf\`, installs next to the official app. Signed with the fork's key; see FORK.md for the fingerprint.

\`\`\`
$(cat "$out/SHA256SUMS")
\`\`\`"
  if ! gh release create "$version" --repo "$fork_repo" --title "GameNative Perf $version" --notes "$notes" \
       "$final_apk" "$out/SHA256SUMS" >>"$build_log" 2>&1; then
    log "release creation failed; see $build_log"
    notify 4 "GameNative $version: release failed" "Branch and tag are pushed; gh release create failed. Log: $build_log"
    exit 4
  fi
fi

echo "$new_tag" > "$last_tag_file"
notify 3 "GameNative Perf $version built" "Upstream $new_tag rebased, built, tested and released on $fork_repo. APK: $final_apk. Not installed anywhere."
log "done: $version"
