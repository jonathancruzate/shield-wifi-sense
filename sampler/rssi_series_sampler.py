#!/usr/bin/env python3
"""Read-only RSSI time-series sampler for the house's fixed Wi-Fi points.

Piggybacks on the EXISTING Winix cloud feed (awareness/winix_feed.py,
300 s cadence + cache guard) — it makes NO cloud calls of its own. Each
run reads ~/workspace/muse-home-link/winix-cloud-state.json and appends
a sample to rssi-series.jsonl when the feed timestamp has advanced.

Why: per-packet/per-scan RSSI variance at fixed receivers is the
coarsest RF body-sensing signal short of CSI (the rung RuView's own
docs call coarse-RSSI mode). Two fixed points exist today (Kitchen and
Bedroom Winix C545s, each reporting its AP-link RSSI via the cloud).
This sampler just stops throwing the series away.

Usage: python3 rssi_series_sampler.py   (idempotent; safe to cron/hook)
"""
import json
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(BASE, "..", "winix-cloud-state.json")
OUT = os.path.join(BASE, "rssi-series.jsonl")


def main() -> int:
    with open(STATE) as f:
        snap = json.load(f)
    ts = snap.get("ts")
    devs = snap.get("devices", {})
    sample = {
        "ts": ts,
        "source": "winix-cloud-state.json (existing 300s awareness feed)",
        "points": {
            k: (int(v["rssi"]) if str(v.get("rssi", "")).lstrip("-").isdigit() else None)
            for k, v in devs.items()
            if "rssi" in v
        },
    }
    # Dedupe against the last WINIX row specifically — since
    # 2026-11-07 the series is interleaved with Shield scan rows
    # (shield_rssi_sampler.py), so the file's last line is no longer
    # necessarily this sampler's own.
    last_ts = None
    if os.path.exists(OUT):
        with open(OUT) as f:
            for line in f:
                try:
                    row = json.loads(line)
                except Exception:
                    continue
                if str(row.get("source", "")).startswith("winix"):
                    last_ts = row.get("ts")
    if ts and ts != last_ts and sample["points"]:
        with open(OUT, "a") as f:
            f.write(json.dumps(sample) + "\n")
        print(f"rssi-sampler: appended {ts} {sample['points']}")
    else:
        print(f"rssi-sampler: no new feed snapshot (last={last_ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
