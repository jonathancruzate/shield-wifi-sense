# Shield Wi-Fi Sense

Wi-Fi wave sensing from an Android TV box you already own — honestly scoped.

An NVIDIA Shield TV (or any Android TV device) is wall-powered, always on, and bolted to one spot in the room. That makes it a surprisingly good fixed RF observation point. This project turns it into one:

- **Android TV app** (`app/`) that samples Wi-Fi state at 2 Hz and writes structured JSON Lines: connected-link info (`WifiInfo`), gateway ping RTT, and — the part that works even on Ethernet — **Wi-Fi scan results** (per-AP RSSI / frequency for everything it can hear).
- **Host sampler** (`sampler/shield_rssi_sampler.py`) that pulls the app's newest sample over ADB on a 5-minute cadence, dedupes on the scan signature, appends one row to an RSSI time series, relaunches the app if it died, and rotates its on-device log.
- **Series + variance consumer pattern** (`sampler/rssi_series_sampler.py` is the sibling Winix-cloud leg from the same system) — per-point RSSI *variance* over time is the sensing signal: bodies moving through the room disturb multipath, and a fixed receiver sees its AP links wobble. A flat series abstains; it carries ~no occupancy evidence and this system treats it that way.

## What it is not

**This is not Wi-Fi CSI, and no APK can make it CSI.** Channel State Information (per-subcarrier amplitude + phase — what projects like RuView / DensePose-from-WiFi consume) lives at the receiving radio's PHY layer and exists only when the radio's firmware exports it (Espressif's `esp_wifi_set_csi`, Nexmon-patched drivers, patched mt76, …). Android exposes no CSI API at any permission level. What Android *does* expose is RSSI-class data, and RSSI supports **coarse presence / room-activity corroboration** — the same rung RuView's own docs assign to their RSSI-only mode. Expect "the living room has been active over the last hour," never pose, tracking, or vitals.

Also verified on the 2019 Shield Pro while building this: **no `android.hardware.wifi.rtt`** (802.11mc FTM ranging) and **no Wi-Fi Aware** — so RTT ranging is out on this hardware too.

## The Ethernet trick

The reference install runs the Shield **wired**. Its Wi-Fi radio is unassociated (`WifiInfo` RSSI reads −127, reported honestly in every sample) — but the radio still *scans*, and scan RSSI for the home's two access points arrives strong (−36 / −38 dBm at 5 GHz in the reference apartment). Scan results are OS-throttled/cached, so the effective new-data cadence is ~30 s to a few minutes: perfect for occupancy *trends*, useless for motion *transients*. If you associate the device to Wi-Fi instead, the same app additionally yields a true 2 Hz connected-link RSSI stream.

## Build & install

No Android Studio needed — `build.sh` compiles the plain-Java app with a project-local toolchain (JDK 17 + Android SDK platform + build-tools under `.toolchain/`, downloaded on first run; nothing installed system-wide):

```bash
./build.sh            # -> shield-wifi-sense-debug.apk (debug-signed)
adb connect <shield-ip>:5555
adb install -r -g shield-wifi-sense-debug.apk
adb shell am start -n com.jonathancruzate.shieldwifisense/.MainActivity
```

Grant location permission (required for scan results on Android 11) and, for unattended sampling while the display sleeps, whitelist it from battery optimization:

```bash
adb shell dumpsys deviceidle whitelist +com.jonathancruzate.shieldwifisense
```

Samples land at `/sdcard/Android/data/com.jonathancruzate.shieldwifisense/files/shield-wifi-sense.jsonl` (see `samples/` for a redacted excerpt).

## Host sampler

```bash
# 1. map your APs in sampler/shield_rssi_sampler.py (APS dict: BSSID -> point name)
# 2. make sure the ADB lane is up (the reference install forwards the
#    Shield's ADB port over a VPN tunnel to 127.0.0.1:5556)
python3 sampler/shield_rssi_sampler.py   # appends to sampler-local rssi-series.jsonl
```

Rows look like the Winix rows in the same series:

```json
{"ts": "2026-11-07T17:00:08.344Z", "source": "shield-wifi-sense scan (adb, awareness poll)",
 "points": {"shield-g3100": -37, "shield-e3200": -38}}
```

(Point names from the author's install; yours will be whatever you put in the `APS` map.)

Operational notes from the reference install:

- At 2 Hz the on-device JSONL grows ~175 MB/day — the sampler truncates it past 3 MB (the app opens the file in append mode per sample, so truncation is safe).
- The sampler never fails its caller: any ADB/app problem prints an `abstain` line and exits 0. A dead sensor should be *visible*, never a stale fact downstream.
- In the author's home-automation stack this series feeds a presence-corroboration layer as an anonymous household vote at low weight (0.15), where RF may corroborate or challenge a location verdict but can never fire, block, or reverse one by itself. Steal that discipline even if you skip the stack.

## Install it with Muse

Give this to your Muse (or any coding agent):

> Clone this repo and set it up for me. Build the APK with `./build.sh` (it bootstraps a project-local Android toolchain — don't install anything system-wide). Connect to my Android TV over ADB (ask me for its IP if you don't know it), install the APK, grant location permission, whitelist it from battery optimization, and launch it. Pull the sample JSONL after ~30 seconds and verify the samples contain real scan results. Then edit `sampler/shield_rssi_sampler.py`: set the `APS` map to my access points' BSSIDs (read them from the pulled samples — my two strongest APs), point the sampler at your ADB lane, and schedule it every 5 minutes to append to an `rssi-series.jsonl`. Report the first appended row to me with the actual RSSI values.

## License

MIT — see `LICENSE`.
