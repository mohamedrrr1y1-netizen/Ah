#!/usr/bin/env sh
# Lightweight bootstrap for environments where the standard Gradle wrapper JAR is unavailable.
# Downloads the pinned official Gradle distribution on first run, then delegates to it.
set -eu
VERSION="8.9"
USER_GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$USER_GRADLE_HOME/hamo-distributions/gradle-$VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle." >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle." >&2; exit 1; }
  mkdir -p "$USER_GRADLE_HOME/hamo-distributions"
  TMP_ZIP="$USER_GRADLE_HOME/hamo-distributions/gradle-$VERSION-bin.zip"
  echo "Downloading the official Gradle $VERSION distribution..."
  curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" --output "$TMP_ZIP"
  TMP_DIR="$USER_GRADLE_HOME/hamo-distributions/extract-$VERSION"
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  unzip -q "$TMP_ZIP" -d "$TMP_DIR"
  mv "$TMP_DIR/gradle-$VERSION" "$DIST_DIR"
  rm -rf "$TMP_DIR" "$TMP_ZIP"
fi
exec "$GRADLE_BIN" "$@"
