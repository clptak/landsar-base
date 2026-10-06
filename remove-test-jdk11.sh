#!/bin/sh
# Removes the JDK 11 installed only for testing LandSAR Base with the Arizona cache.
# Your default Java (openjdk 27) is not touched.
set -e
brew uninstall openjdk@11
# Optional: the scratchpad test copy and its logs (uncomment to delete)
# rm -rf /private/tmp/claude-501/-Users-paulclifton-CloudTAK/b9157874-4671-465b-8f63-c2fd947ccde7/scratchpad/run
# rm -f  /private/tmp/claude-501/-Users-paulclifton-CloudTAK/b9157874-4671-465b-8f63-c2fd947ccde7/scratchpad/landsar-run.log
echo "openjdk@11 removed."
