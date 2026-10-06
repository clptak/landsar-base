#!/bin/sh
# Start LandSAR Base with the Java 11 JDK. The patched geocache loader lives in dependencies/osppre-core-2.0.3.jar
# (apply with: python3 tools/patch_offline_cache.py).
# Run from anywhere; logs also go to ~/landsar-server.log. Stop with Ctrl+C, or:
#   pkill -9 -f roger-core-1.4.0-fullRelease.jar
cd "$(dirname "$0")/.." || exit 1
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@11/bin/java}"
exec "$JAVA" -cp "roger-core-dependencies/dependencies/*:roger-core-1.4.0-fullRelease.jar" \
  com.bbn.roger.launcher.CommandLineLauncher --configFile config.json 2>&1 | tee "$HOME/landsar-server.log"
