#!/usr/bin/env python3
"""Work around a bug in the disk geocache loader (osppre-core 2.0.3).

OfflineDataCache.loadAreaDataFromCache(BoundingBox, String, LandCoverMetaData) replaces the
cache's tile index (dirInfoHash) with a copy trimmed to the tiles the *current* request used.
After the first search, any search that needs a tile outside that set fails with
"Could not find directory that contains longitude/latitude ...".

LandSAR loads this library through its own plugin class loader from
dependencies/osppre-core-2.0.3.jar (a git-ignored copy that `./gradlew copyRuntimeLibs`
generates from provided_lib/). This script rewrites that copy with one patched class,
replacing the 3-byte `putfield dirInfoHash` with `pop; pop; nop` so the full index is kept.
The pristine original stays in provided_lib/. Side effect: tiles stay loaded once read
(about 6 MB per 0.25 x 0.3 deg tile).

    python3 tools/patch_offline_cache.py            # apply the patch
    python3 tools/patch_offline_cache.py --revert   # restore the unpatched jar

Re-run after any `./gradlew copyRuntimeLibs`, which overwrites the patched copy.
"""
import os
import shutil
import struct
import sys
import zipfile

JAR = "provided_lib/com/metsci/jpra/osppre-core/2.0.3/osppre-core-2.0.3.jar"   # pristine original
DEPLOYED = "dependencies/osppre-core-2.0.3.jar"                                   # what LandSAR loads
CLASS = "com/metsci/osppre/groundMovement/OfflineDataCache.class"
METHOD = "loadAreaDataFromCache"
DESC_PREFIX = "(Lcom/bbn/landsar/geospatial/BoundingBox;Ljava/lang/String;"
PUTFIELD, POP, NOP = 0xB5, 0x57, 0x00


def main():
    if "--revert" in sys.argv:
        shutil.copyfile(JAR, DEPLOYED)
        print("restored unpatched jar ->", DEPLOYED)
        return
    data = bytearray(zipfile.ZipFile(JAR).read(CLASS))

    # Constant pool: keep UTF8 entries and remember which index is the dirInfoHash field name.
    count = struct.unpack(">H", data[8:10])[0]
    pos, utf8, i = 10, {}, 1
    fieldref_name = {}      # Fieldref index -> NameAndType index
    nat = {}                # NameAndType index -> name UTF8 index
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            utf8[i] = data[pos + 3:pos + 3 + n].decode("utf8", "replace")
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 9:
            fieldref_name[i] = struct.unpack(">H", data[pos + 3:pos + 5])[0]
            pos += 5
        elif tag == 12:
            nat[i] = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            pos += 5
        elif tag in (10, 11, 17, 18):
            pos += 5
        elif tag == 15:
            pos += 4
        i += 1
    field_refs = [r for r, nt in fieldref_name.items() if utf8.get(nat.get(nt)) == "dirInfoHash"]
    if len(field_refs) != 1:
        sys.exit("expected exactly one Fieldref to dirInfoHash, found %d" % len(field_refs))
    ref = field_refs[0]

    # Skip header, interfaces, fields; then find the target method's Code attribute.
    pos += 6
    pos += 2 + 2 * struct.unpack(">H", data[pos:pos + 2])[0]
    nfields = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    for _ in range(nfields):
        nattrs = struct.unpack(">H", data[pos + 6:pos + 8])[0]
        pos += 8
        for _ in range(nattrs):
            pos += 6 + struct.unpack(">I", data[pos + 2:pos + 6])[0]
    nmethods = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    code_start = None
    for _ in range(nmethods):
        _, name_i, desc_i, nattrs = struct.unpack(">HHHH", data[pos:pos + 8])
        pos += 8
        for _ in range(nattrs):
            attr_i, length = struct.unpack(">HI", data[pos:pos + 6])
            if (utf8[name_i] == METHOD and utf8[desc_i].startswith(DESC_PREFIX)
                    and utf8[attr_i] == "Code"):
                code_start = pos + 6
            pos += 6 + length
    if code_start is None:
        sys.exit("method %s%s... not found" % (METHOD, DESC_PREFIX))

    code_len = struct.unpack(">I", data[code_start + 4:code_start + 8])[0]
    code = code_start + 8
    ref_bytes = struct.pack(">H", ref)
    # Find `putfield dirInfoHash` (b5 <ref>) inside this method's bytecode.
    hits = [o for o in range(code_len - 2)
            if data[code + o] == PUTFIELD and data[code + o + 1:code + o + 3] == ref_bytes]
    if len(hits) != 1:
        sys.exit("expected exactly one putfield dirInfoHash in %s, found %d" % (METHOD, len(hits)))
    off = hits[0]
    data[code + off:code + off + 3] = bytes([POP, POP, NOP])

    # Rebuild the jar from the pristine original with only this class replaced.
    tmp = DEPLOYED + ".tmp"
    with zipfile.ZipFile(JAR) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            dst.writestr(item, bytes(data) if item.filename == CLASS else src.read(item.filename))
    os.replace(tmp, DEPLOYED)
    print("patched putfield at bytecode offset %d -> %s" % (off, DEPLOYED))


if __name__ == "__main__":
    main()
