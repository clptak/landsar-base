#!/usr/bin/env python3
"""Apply every local patch to the LandSAR Base jars, in the right order.

    python3 tools/apply_patches.py            # apply all
    python3 tools/apply_patches.py --revert   # undo all

Order matters: patch_sample_builder.py rebuilds the SDK jars from the pristine copy in provided_lib/, so
patch_resolution.py (which edits the finished jars in place) must come after it. Run this after any
`./gradlew copyRuntimeLibs`, which regenerates dependencies/ from pristine jars, and then
`python3 tools/build_terrain_walk.py` if the model changed. Restart LandSAR afterwards.

  patch_offline_cache.py   disk cache no longer forgets tiles after the first search
  patch_sample_builder.py  example model: compass degrees, no endless loop
  patch_resolution.py      probability maps: up to 32000 cells (30 m minimum) instead of 2500 (200 m)
"""
import subprocess
import sys

STEPS = ["tools/patch_offline_cache.py", "tools/patch_sample_builder.py", "tools/patch_resolution.py"]


def run(script, *args):
    print("==", script, *args, flush=True)
    subprocess.run([sys.executable, script, *args], check=True)


def main():
    if "--revert" in sys.argv:
        run("tools/patch_resolution.py", "--revert")          # in-place edit, so undo first
        run("tools/patch_sample_builder.py", "--revert")      # restores the pristine SDK jars
        run("tools/patch_offline_cache.py", "--revert")       # restores the pristine osppre-core jar
    else:
        for script in STEPS:
            run(script)


if __name__ == "__main__":
    main()
