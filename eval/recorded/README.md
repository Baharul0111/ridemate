# Recorded outputs (filled by live runs, not by the build)

| Path | Written by | Shape |
|---|---|---|
| `channel_a/<sha256>.json` | `harness/record_channel_a.py`, through the metered API or gateway | contracts §6 recorded Channel A, plus `source`, `request_id`, `latency_ms` |
| `ocr/<sha256>.json` | the owner's phone (Recorder / AccuracyTest export of ML Kit Text Recognition v2) | contracts §6 OCR, `engine: "mlkit-text-v2"` |

Only images the public repo already contains (the fictional demo screens and the injection screens) are
recorded here. Real-app captures and the golden set stay in the private `ridemate-golden` repo (L6).
- The backend can serve these files as its "recorded" fallback for demo reads (M9).
- The Kotlin AccuracyTest replays them, so CI spends nothing.
