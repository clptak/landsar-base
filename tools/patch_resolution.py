#!/usr/bin/env python3
"""Raise the resolution of LandSAR's probability maps.

The SDK limits every probability map to 2500 cells and a cell side of at least 0.2 km
(ContainmentMap.MAX_NUM_CELLS / MIN_CELL_SIDE_KM). The numbers are compiled into several classes, so a map
for a 30 x 27 km search box has cells of about 570 m. ProbabilityDistribution.validate() requires every map to
have exactly the size computed from those two numbers, so they must change together, everywhere:

    motion-model-sdk   ContainmentMap, ProbabilityDistribution              (plugins/ and dependencies/ copies)
    roger-osppre       LandSARMotionModelManagerPlugin                       (plugins/, tracked in git)
    osppre-core        MultiScenarioState, MotionModelSearchUtils            (dependencies/ copy)

This script changes the instruction pairs `sipush 2500` + `ldc2_w <0.2>` (and the two constants themselves) to
NEW_MAX_CELLS and NEW_MIN_CELL_KM, in place and without changing any class's size. It refuses to touch a class
in which the 0.2 constant is used anywhere other than those pairs.

    python3 tools/patch_resolution.py            # apply
    python3 tools/patch_resolution.py --revert   # back to 2500 cells / 0.2 km

It works on the jars as they are, so it can run before or after the other patch scripts (tools/apply_patches.py
runs them all). Restart LandSAR afterwards. A finer map has more cells to store, draw and search: the default
below is about 13 times as many cells as before.
"""
import os
import struct
import sys
import zipfile

OLD_MAX_CELLS, OLD_MIN_CELL_KM = 2500, 0.2
NEW_MAX_CELLS, NEW_MIN_CELL_KM = 32000, 0.03     # sipush needs a value below 32768

TARGETS = [
    # (jar, class, also patch the int constant MAX_NUM_CELLS in the pool)
    ("plugins/motion-model-sdk-0.0.6-SNAPSHOT.jar", "com/bbn/landsar/searchtheory/ContainmentMap.class", True),
    ("plugins/motion-model-sdk-0.0.6-SNAPSHOT.jar", "com/bbn/landsar/motionmodel/ProbabilityDistribution.class", False),
    ("dependencies/motion-model-sdk-0.0.6-SNAPSHOT.jar", "com/bbn/landsar/searchtheory/ContainmentMap.class", True),
    ("dependencies/motion-model-sdk-0.0.6-SNAPSHOT.jar", "com/bbn/landsar/motionmodel/ProbabilityDistribution.class", False),
    ("plugins/roger-osppre-2.0.3.jar", "com/bbn/roger/osppre/plugin/motion_model_arch/LandSARMotionModelManagerPlugin.class", False),
    ("dependencies/osppre-core-2.0.3.jar", "com/metsci/osppre/api/groundMovement/MultiScenarioState.class", False),
    ("dependencies/osppre-core-2.0.3.jar", "com/metsci/osppre/search/MotionModelSearchUtils.class", False),
]


def pool_entries(data):
    """Returns ({index: (tag, file_offset_of_value)}, doubles {index: value}, ints {index: value})."""
    count = struct.unpack(">H", data[8:10])[0]
    pos, i, doubles, ints = 10, 1, {}, {}
    while i < count:
        tag = data[pos]
        if tag == 1:
            pos += 3 + struct.unpack(">H", data[pos + 1:pos + 3])[0]
        elif tag == 3:
            ints[i] = (pos + 1, struct.unpack(">i", data[pos + 1:pos + 5])[0])
            pos += 5
        elif tag == 4:
            pos += 5
        elif tag == 5:
            pos += 9
            i += 1
        elif tag == 6:
            doubles[i] = (pos + 1, struct.unpack(">d", data[pos + 1:pos + 9])[0])
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        i += 1
    return doubles, ints


def patch_class(data, old_max, old_min, new_max, new_min, patch_int_constant):
    """Returns (new_bytes, number_of_sites) or (None, sites_already_in_new_state)."""
    data = bytearray(data)
    doubles, ints = pool_entries(data)

    def find_sites(maxv, minv):
        idxs = [i for i, (_, v) in doubles.items() if abs(v - minv) < 1e-12]
        sites = []
        for idx in idxs:
            pat = bytes([0x11]) + struct.pack(">h", maxv) + bytes([0x14]) + struct.pack(">H", idx)
            start = 0
            while True:
                p = bytes(data).find(pat, start)
                if p < 0:
                    break
                sites.append((p, idx))
                start = p + 1
            # every ldc2_w of this constant must be one of our sites
            ldc = bytes([0x14]) + struct.pack(">H", idx)
            total, start = 0, 0
            while True:
                p = bytes(data).find(ldc, start)
                if p < 0:
                    break
                total += 1
                start = p + 1
            if total != len([s for s in sites if s[1] == idx]):
                sys.exit("the 0.2 constant is used outside the sipush/ldc2_w pairs - refusing to patch this class")
        return sites

    old_sites = find_sites(old_max, old_min)
    if not old_sites:
        return None, len(find_sites(new_max, new_min))
    for p, idx in old_sites:
        data[p + 1:p + 3] = struct.pack(">h", new_max)
        off, _ = doubles[idx]
        data[off:off + 8] = struct.pack(">d", new_min)
    if patch_int_constant:
        for off, v in ints.values():
            if v == old_max:
                data[off:off + 4] = struct.pack(">i", new_max)
    return bytes(data), len(old_sites)


def rewrite_jar(path, replacements):
    tmp = path + ".tmp"
    with zipfile.ZipFile(path) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, replacements.get(item.filename, zin.read(item.filename)))
    os.replace(tmp, path)


def main():
    revert = "--revert" in sys.argv
    old_max, old_min, new_max, new_min = ((NEW_MAX_CELLS, NEW_MIN_CELL_KM, OLD_MAX_CELLS, OLD_MIN_CELL_KM) if revert
                                          else (OLD_MAX_CELLS, OLD_MIN_CELL_KM, NEW_MAX_CELLS, NEW_MIN_CELL_KM))
    by_jar = {}
    for jar, cls, with_int in TARGETS:
        if os.path.exists(jar):
            by_jar.setdefault(jar, []).append((cls, with_int))
    for jar, classes in by_jar.items():
        repl = {}
        with zipfile.ZipFile(jar) as z:
            for cls, with_int in classes:
                new, sites = patch_class(z.read(cls), old_max, old_min, new_max, new_min, with_int)
                if new is None:
                    print("%-52s %-28s already %s (%d site(s))" % (jar, os.path.basename(cls), "reverted" if revert else "patched", sites))
                else:
                    repl[cls] = new
                    print("%-52s %-28s %s %d site(s)" % (jar, os.path.basename(cls), "reverted" if revert else "patched", sites))
        if repl:
            rewrite_jar(jar, repl)
    print("%s: maps %s cells max, %s km minimum cell side" % ("reverted" if revert else "done", new_max, new_min))


if __name__ == "__main__":
    main()
