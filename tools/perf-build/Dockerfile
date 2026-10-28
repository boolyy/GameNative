# Build image for GameNative Perf (see FORK.md).
# Only the JDK, the Android command-line tools and a few CLI tools live here.
# The SDK packages, NDK and Gradle caches are bind-mounted from the host so the
# image stays small and the caches survive image rebuilds:
#   /sdk     <- ~/.cache/gamenative-perf/android-sdk
#   /gradle  <- ~/.cache/gamenative-perf/gradle
#   /home/builder <- ~/.cache/gamenative-perf/home  (debug keystore, misc dotfiles)
#   /src     <- the fork checkout
#   /keys    <- ~/gamenative-perf/keys (read-only, Phase 2 signing key)
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS=commandlinetools-linux-15859902_latest.zip
# From https://developer.android.com/studio#command-line-tools-only (checked 2026-10-28)
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip zip curl git ca-certificates file python3 \
 && rm -rf /var/lib/apt/lists/*

RUN set -eux; cd /tmp; \
    curl -fsSLO "https://dl.google.com/android/repository/${CMDLINE_TOOLS}"; \
    echo "${CMDLINE_TOOLS_SHA256}  ${CMDLINE_TOOLS}" | sha256sum -c -; \
    mkdir -p /opt/android/cmdline-tools; \
    unzip -q "${CMDLINE_TOOLS}" -d /opt/android/cmdline-tools; \
    mv /opt/android/cmdline-tools/cmdline-tools /opt/android/cmdline-tools/latest; \
    rm -f "${CMDLINE_TOOLS}"

ENV ANDROID_HOME=/sdk \
    ANDROID_SDK_ROOT=/sdk \
    ANDROID_USER_HOME=/home/builder/.android \
    GRADLE_USER_HOME=/gradle \
    HOME=/home/builder \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8" \
    PATH="/opt/android/cmdline-tools/latest/bin:/sdk/platform-tools:${PATH}"

COPY entrypoint.sh /usr/local/bin/perf-entrypoint
RUN chmod 755 /usr/local/bin/perf-entrypoint

WORKDIR /src
ENTRYPOINT ["/usr/local/bin/perf-entrypoint"]
