#!/usr/bin/env python3
"""Overnight report from the phone's n0rma.db.

  python3 tools/morning_report.py            # pulls the DB over adb, last 12 h
  python3 tools/morning_report.py DB [HOURS] # use an already-pulled DB
"""
import collections
import datetime
import os
import sqlite3
import subprocess
import sys

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
APP = "io.github.sloppytopp.n0rma"
OWN = ("5tayin", "stayin", "travelsafe", "goingsomewhere", "HP-Print", "DIRECT-roku")
KEYWORDS = ("cams", "tile", "airtag", "find", "drone", "rid", "cam")


def pull(dest):
    with open(dest, "wb") as f:
        subprocess.run([ADB, "exec-out", f"run-as {APP} cat databases/n0rma.db"], stdout=f, check=True)
    return dest


def fmt(t):
    return datetime.datetime.fromtimestamp(t / 1000).strftime("%m-%d %H:%M")


def main():
    db = sys.argv[1] if len(sys.argv) > 1 else pull("n0rma.db")
    hours = float(sys.argv[2]) if len(sys.argv) > 2 else 12
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    mx = c.execute("select max(ts) from presence").fetchone()[0]
    cut = mx - int(hours * 3600 * 1000)
    rows = c.execute("select ts,key,label,kind,rssi from presence").fetchall()
    first, last, best, lab = {}, {}, {}, {}
    n = collections.Counter()
    for ts, k, l, _kind, r in rows:
        first.setdefault(k, ts)
        if ts >= cut:
            n[k] += 1
            last[k] = ts
            best[k] = max(best.get(k, -999), r)
            lab[k] = l
    print(f"window {fmt(cut)} -> {fmt(mx)} | rows in window: {sum(r[0] >= cut for r in rows)}")
    print(f"sources active in window: {len(n)}")

    print("\nNEW (first ever seen in window):")
    for k in sorted(n, key=lambda k: first[k]):
        if first[k] >= cut:
            print(f"  {fmt(first[k])}  {k:32} {lab[k]!r:28} best {best[k]} dBm  x{n[k]}")

    print("\nSTRONGEST in window (>= -60 dBm, non-own SSIDs):")
    for k in sorted(best, key=lambda k: -best[k]):
        if best[k] >= -60 and not str(lab[k]).startswith(OWN):
            print(f"  {k:32} {lab[k]!r:28} {best[k]} dBm x{n[k]} last {fmt(last[k])}")

    print("\nTRACKER/DRONE/CAMERA-ish labels in window:")
    for k in n:
        if any(s in (lab[k] or "").lower() + k for s in KEYWORDS):
            print(f"  {k:32} {lab[k]!r:28} best {best[k]} x{n[k]} last {fmt(last[k])}")

    print("\nEVENTS in window:")
    for ts, dom, lvl, msg in c.execute("select ts,domain,level,msg from events where ts>=? order by ts", (cut,)):
        print(" ", fmt(ts), dom, lvl, msg[:110])


if __name__ == "__main__":
    main()
