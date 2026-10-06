#!/usr/bin/env python3
"""Compile the terrain-aware random-walk motion model into plugins/landsar-terrain-walk-0.1.0.jar.

    python3 tools/build_terrain_walk.py

The source is models/terrain-walk/src. It is compiled for Java 8 against the SDK jar in plugins/ and the
jars in dependencies/ (run ./gradlew copyRuntimeLibs first if that folder is empty). The jar carries a
plugin-manifest.json so that ROGER finds the plugin class. To use the model, register it in config.json
(see models/terrain-walk/README.md) and restart LandSAR.

Needs a JDK with javac (default: Homebrew openjdk@11; override with the JAVAC environment variable).
"""
import glob
import json
import os
import subprocess
import sys
import tempfile
import zipfile

SRC = "models/terrain-walk/src"
SDK_JAR = "plugins/motion-model-sdk-0.0.6-SNAPSHOT.jar"
OUT_JAR = "plugins/landsar-terrain-walk-0.1.0.jar"
PLUGIN_CLASS = "landsar.terrainwalk.TerrainWalkMotionModelPlugin"
JAVAC = os.environ.get("JAVAC", "/opt/homebrew/opt/openjdk@11/bin/javac")

MANIFEST = {
    "pluginContainerName": "LandSAR Terrain Walk Motion Model",
    "description": "A terrain-aware random-walk motion model for a lost person on foot.",
    "plugins": [{
        "className": PLUGIN_CLASS,
        "author": "landsar-base local",
        "description": "Many simulated walkers; speed set by landcover and slope; schedule, water and exclusion aware.",
        "version": "0.1.0",
        "dependencies": ["motion-model-sdk-0.0.6-SNAPSHOT.jar"],
    }],
}


def main():
    deps = glob.glob("dependencies/*.jar")
    if not deps:
        sys.exit("dependencies/ is empty - run ./gradlew copyRuntimeLibs first")
    sources = glob.glob(SRC + "/**/*.java", recursive=True)
    if not sources:
        sys.exit("no sources under " + SRC)
    with tempfile.TemporaryDirectory() as tmp:
        classes = os.path.join(tmp, "classes")
        os.makedirs(classes)
        cp = os.pathsep.join([SDK_JAR] + deps)
        subprocess.run([JAVAC, "--release", "8", "-Xlint:-options", "-cp", cp, "-d", classes] + sources, check=True)
        tmp_jar = OUT_JAR + ".tmp"
        with zipfile.ZipFile(tmp_jar, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("plugin-manifest.json", json.dumps(MANIFEST, indent=2))
            for root, _, files in os.walk(classes):
                for f in files:
                    full = os.path.join(root, f)
                    z.write(full, os.path.relpath(full, classes))
    os.replace(tmp_jar, OUT_JAR)
    print("built", OUT_JAR)


if __name__ == "__main__":
    main()
