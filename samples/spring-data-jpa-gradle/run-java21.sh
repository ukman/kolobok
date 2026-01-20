#!/bin/bash

set -euo pipefail

JAVA_HOME="/Users/ukman/.sdkman/candidates/java/21.0.6-tem"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ -x "./gradlew" ]; then
  ./gradlew bootRun
else
  GRADLE_VERSION="8.6"
  GRADLE_BASE=".gradle-bin"
  GRADLE_DIR="$GRADLE_BASE/gradle-$GRADLE_VERSION"
  GRADLE_ZIP="$GRADLE_BASE/gradle-$GRADLE_VERSION-bin.zip"

  if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
    mkdir -p "$GRADLE_BASE"
    if [ ! -f "$GRADLE_ZIP" ]; then
      curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
    fi
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_BASE"
  fi

  "$GRADLE_DIR/bin/gradle" bootRun
fi
