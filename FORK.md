# GameNative Perf (fork)

This is a fork of [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative),
kept by [boolyy](https://github.com/boolyy). It adds **FrameBoost**: GameNative tells Android
how long each frame's CPU work took against the frame-time target, so the scheduler gives
CPU-bound games the fast core and high clocks only when they need them.

It installs **side by side** with the official app as package `app.gamenative.perf`
("GameNative Perf"). The official `app.gamenative` is left untouched.

The plan, decisions and status log live in a separate repo:
`android-gaming-setup/docs/gamenative-fork/PLAN.md`.

## Why

CPU-bound Windows games under GameNative (SiNiSistar 2, Cult of the Lamb on the Retroid Pocket
Nova) ran at 17-30 FPS. The game's main thread spends most of its time waiting on `wineserver`,
so Android's scheduler never sees it as busy, never moves it to the prime core and never raises
the clock. Pinning the thread to the prime core by hand (root, via the vendor's `PServerBinder`)
took SiNiSistar 2 from 17 to 58 FPS, but it runs the core flat out all the time.

FrameBoost does the same thing properly:

- **ADPF backend** (no root): `PerformanceHintManager` hint session over the game's busy threads
  and `wineserver`. Every frame reports the actual CPU work (present interval minus the FPS
  limiter's injected wait) against the target. Android boosts and backs off on its own.
- **Root backend** (fallback when the power HAL has no hint-session support): through the
  existing `PServerDriver`, keeps the prime core online, pins the main thread to it, moves
  `wineserver` off the efficiency cluster, and runs a closed loop on the prime cluster's
  max frequency: lower it when p90 work time has headroom, raise it on misses.

Both devices this is for (Retroid Pocket Nova, AYN Thor: QCS8550, Android 13, `PServerBinder`)
are discovered at runtime; nothing is hardcoded to a device.

## Branches and tags

- `master`: untouched mirror of upstream `master`.
- `perf/<upstream tag>`: the patch rebased onto that upstream stable release, e.g. `perf/v1.3.0`.
- `<upstream tag>-perf.<N>`: release tags of this fork, e.g. `v1.3.0-perf.1`.

Only upstream **stable** releases are tracked (`/releases/latest`, no prereleases). A daily job
on the maintainer's build host rebases the patch onto each new stable tag, builds, tests, and
publishes a GitHub release here (see `tools/perf-build/sync.sh`). Rebase conflicts are never
auto-resolved; they raise an alert and wait for a human.

## Building

Everything runs in Docker; no Android SDK on the host.

```bash
tools/perf-build/build.sh :app:assembleLegacyRelease            # unmodified app, debug-signed
PERF_KEYSTORE_PROPS=~/gamenative-perf/keys/keystore.properties \
  tools/perf-build/build.sh --out ~/gamenative-perf/out/v1.3.0-perf.1 :app:assembleLegacyPerf
tools/perf-build/build.sh :app:testLegacyDebugUnitTest          # upstream + FrameBoost unit tests
```

- `tools/perf-build/Dockerfile`: Temurin 17 + Android command-line tools (checksum-verified).
  SDK packages, the NDK and Gradle caches are bind-mounted from `~/.cache/gamenative-perf`.
- `tools/perf-build/build.sh`: runs Gradle in the container as the calling user, with a 6 GB
  daemon heap and 8 workers so the build host stays usable. Validates the Gradle wrapper jar
  against Gradle's published checksum first (same as upstream CI).
- `build.sh --shell` opens a shell in the container.

The `legacy` flavor (targetSdk 28) is what the official `gamenative-vX.apk` ships, so that is
the flavor built here: `:app:assembleLegacyPerf`.

### Build type `perf`

Modelled on upstream's `release-gold`:

- `applicationIdSuffix = ".perf"`, label "GameNative Perf" (`app/src/perf/res/values/strings.xml`).
- `BuildConfig.PERF_FORK = true` (false in every other build type).
- `versionName = "<upstream>-perf.<N>"`, `versionCode = <upstream versionCode> * 100 + N`.
- Signing config read from the file named by the `PERF_KEYSTORE_PROPS` environment variable
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is resolved relative to
  that file). Nothing key-related is in the repo.
- Build-time secrets (`POSTHOG_API_KEY`, `STEAMGRIDDB_API_KEY`, `CLOUD_PROJECT_NUMBER`) are
  left empty.

Signing certificate SHA-256 (not secret; use it to verify downloads with
`apksigner verify --print-certs`):

```
(filled in once the key is generated; see the status log in PLAN.md)
```

Losing the key means the fork can no longer be updated in place; the keystore and its
passwords live only on the maintainer's build host and in its backup.

## What is different from upstream

Kept deliberately small so the patch rebases cleanly. Full list maintained here.

### Side-by-side package: hardcoded `app.gamenative` paths

Upstream hardcodes its own data directory in a few places. With package `app.gamenative.perf`
the data directory is `/data/data/app.gamenative.perf`, so these were changed to derive the path
from the running package:

(filled in during Phase 2)

Not changed on purpose:

- Custom URL schemes (`app.gamenative://oauth/callback` for Nexus Mods, `gamenative://run`,
  `gamenative://discord-linked`, `home://pluvia`, `nxm`). The Nexus OAuth redirect URI is
  registered on their side, so it cannot change. With both apps installed Android asks which
  app should open such a link.

### Uploads to GameNative's servers are off

With `PERF_FORK` set, the app does not send session reports, compatibility submissions or
debug reports, and PostHog analytics is not initialized. A modified build must not feed the
community database, and the API requires a Play Integrity token tied to upstream's signing
key, so the calls would fail anyway. Reads (Browse Configs, known configs) may also fail for
the same reason; that is accepted.

### FrameBoost

New package `app.gamenative.frameboost` plus these hooks into upstream code:

(filled in during Phase 3)

Settings → Performance → **FrameBoost**: Off / Auto / Hint API only / Root only (default Auto).
Per game, the container environment variable `GN_FRAMEBOOST=off|auto|adpf|root` overrides it.
FrameBoost stays off while GameNative's own Power Control is active for the game.

Logs: logcat tag `FrameBoost`, and a JSONL file per session under
`/sdcard/Android/data/app.gamenative.perf/files/frameboost/`.

## License

GPL-3.0, same as upstream. See `LICENSE`.
