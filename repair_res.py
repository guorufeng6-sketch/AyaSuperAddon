#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
repair_res.py -- fix apktool's case-collision resource loss on Windows.

Background
----------
The official RayNeo APK contains many pairs of resource files whose names
differ ONLY by letter case (e.g. res/Uk.xml AND res/uK.xml are two different
resources). This is the vendor's resource-name obfuscation scheme.

apktool on a case-insensitive filesystem (Windows) cannot materialise both
members of such a pair when it stages raw resources under unknown/res/, so
it silently drops one of them. resources.arsc, however, is copied verbatim
and still points at the dropped path (res/Uk.xml). At runtime Android resolves
resource -> arsc -> "res/Uk.xml" -> ZIP lookup (case-sensitive) -> NOT FOUND
-> drawable load fails.

Symptom: a foreground-service notification whose small icon is one of those
dropped resources throws BadForegroundServiceNotificationException and the
system kills the app on every launch.

Fix
---
After apktool builds the unsigned APK, copy every res/* entry that exists in
the ORIGINAL apk but is missing (case-sensitively) from the built apk back in.
Run this on the UNSIGNED apk, then zipalign + apksigner as usual.

Usage:
    python repair_res.py <built.apk> <original.apk> <out.apk>
"""
import sys
import zipfile

def main():
    if len(sys.argv) != 4:
        print("usage: repair_res.py <built.apk> <original.apk> <out.apk>")
        return 2
    built, original, out = sys.argv[1], sys.argv[2], sys.argv[3]

    with zipfile.ZipFile(built) as zb, zipfile.ZipFile(original) as zo:
        built_names = set(zb.namelist())
        orig_names = set(zo.namelist())

        # every original entry missing from the build, grouped by prefix
        missing_all = sorted(orig_names - built_names)
        missing_res = [n for n in missing_all if n.startswith("res/")]
        missing_other = [n for n in missing_all if not n.startswith("res/")]

        print("[repair] built entries      : %d" % len(built_names))
        print("[repair] original entries   : %d" % len(orig_names))
        print("[repair] missing total      : %d" % len(missing_all))
        print("[repair] missing under res/ : %d  (will restore)" % len(missing_res))
        print("[repair] missing elsewhere  : %d" % len(missing_other))
        for n in missing_other[:40]:
            print("           (skipped) " + n)

        payload = [(zo.getinfo(n), zo.read(n)) for n in missing_res]

        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
            for info in zb.infolist():
                if info.is_dir():
                    zout.writestr(info, b"")
                    continue
                zout.writestr(info, zb.read(info.filename))
            for info, data in payload:
                zout.writestr(info, data)

    # verify
    with zipfile.ZipFile(built) as zb, zipfile.ZipFile(out) as zx, zipfile.ZipFile(original) as zo:
        need = [n for n in zo.namelist() if n.startswith("res/")]
        have = set(zx.namelist())
        still = [n for n in need if n not in have]
        print("[repair] after: original res entries present = %d/%d, still missing = %d"
              % (len(need) - len(still), len(need), len(still)))
        for n in still[:20]:
            print("           MISSING " + n)
        # spot-check the notification icon path referenced by arsc (id 0x7f0800b0)
        print("[repair] res/Uk.xml present in out : %s" % ("res/Uk.xml" in have))
        print("[repair] res/uK.xml present in out : %s" % ("res/uK.xml" in have))
    print("[repair] wrote " + out)
    return 0

if __name__ == "__main__":
    sys.exit(main())
