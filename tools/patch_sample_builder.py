#!/usr/bin/env python3
"""Fix two bugs in the example motion model's SampleBuilder (motion-model-sdk 0.0.6-SNAPSHOT).

  1. The direction was used as a raw angle in radians (counter-clockwise from east), although the UI says
     "direction (in degrees [0-360))". It now takes a compass bearing: 0 = north, 90 = east.
  2. A search could hang forever: once a path reached its maximum distance, endTime was reset on every
     later step, so the sample never ended whenever rounding left the path length >= the maximum.

The corrected source is tools/patches/SampleBuilder.java (based on the SDK's source). This script compiles
it (Java 8 target) and rebuilds
    plugins/motion-model-sdk-0.0.6-SNAPSHOT.jar        (tracked in git)
    dependencies/motion-model-sdk-0.0.6-SNAPSHOT.jar   (generated; recreated by ./gradlew copyRuntimeLibs)
from the pristine jar in provided_lib/, which is never modified. Restart LandSAR afterwards.

    python3 tools/patch_sample_builder.py            # compile and apply
    python3 tools/patch_sample_builder.py --revert   # restore the pristine jars

Re-run after any `./gradlew copyRuntimeLibs`. Needs a JDK with javac (default: Homebrew openjdk@11;
override with the JAVAC environment variable).
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

PRISTINE = "provided_lib/com/bbn/landsar/motion-model-sdk/0.0.6-SNAPSHOT/motion-model-sdk-0.0.6-SNAPSHOT.jar"
TARGETS = ["plugins/motion-model-sdk-0.0.6-SNAPSHOT.jar", "dependencies/motion-model-sdk-0.0.6-SNAPSHOT.jar"]
SOURCE = "tools/patches/SampleBuilder.java"
PKG = "com/bbn/landsar/motionmodel/path/"
JAVAC = os.environ.get("JAVAC", "/opt/homebrew/opt/openjdk@11/bin/javac")


def compile_source(outdir):
    deps = glob.glob("dependencies/*.jar")
    if not deps:
        sys.exit("dependencies/ is empty - run ./gradlew copyRuntimeLibs first (needed for the compile classpath)")
    cp = os.pathsep.join([PRISTINE] + deps)
    subprocess.run([JAVAC, "--release", "8", "-nowarn", "-cp", cp, "-d", outdir, SOURCE],
                   check=True, stderr=subprocess.DEVNULL)
    classes = {}
    for name in os.listdir(os.path.join(outdir, PKG)):
        with open(os.path.join(outdir, PKG, name), "rb") as f:
            classes[PKG + name] = f.read()
    if PKG + "SampleBuilder.class" not in classes:
        sys.exit("compile produced no SampleBuilder.class")
    return classes


def rebuild(dst, replacements):
    tmp = dst + ".tmp"
    with zipfile.ZipFile(PRISTINE) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, replacements.get(item.filename, zin.read(item.filename)))
    os.replace(tmp, dst)


def main():
    if "--revert" in sys.argv:
        for t in TARGETS:
            if os.path.isdir(os.path.dirname(t)):
                shutil.copyfile(PRISTINE, t)
                print("restored", t)
        return
    with tempfile.TemporaryDirectory() as tmp:
        classes = compile_source(tmp)
    for t in TARGETS:
        if os.path.isdir(os.path.dirname(t)):
            rebuild(t, classes)
            print("patched", t, "(replaced %s)" % ", ".join(sorted(os.path.basename(c) for c in classes)))


if __name__ == "__main__":
    main()
