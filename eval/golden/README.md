# Synthetic golden set

```sh
cd eval && uv run python golden/generate_golden.py [--out DIR] [--count 165] [--seed 20261028]
```

The generator writes the screens into `--out`, which defaults to `eval/private/golden` and is gitignored. It
refuses any other path inside the public repo, because golden data must never be committed (architecture L6).
The owner copies the output into the private `ridemate-golden` repo, next to the real masked captures exported
by the Recorder. From there `harness/sync_golden_to_volume.py` pushes it to the Modal Volume for live evals.

```
labels.json                 contracts §6 labels; "file" is relative to this directory
images/<name>.png|jpg       1080×2400 screens of the fictional providers
ocr/<sha256>.json           ideal synthetic OCR (same shape as recorded ML Kit output)
channel_a/<sha256>.json     perfect-reader Channel A output with the expected gate outcome
```

## What the default 165 screens contain
The set has 55 screens per provider, 147 fare screens and 18 driver screens. The plan is a pure function of
`(count, seed)`. Each feature is scheduled from the screen's per-provider index, so the quotas hold by
construction, and `tests/test_golden.py` checks them without rendering:

- **Per provider:** at least 15 struck/promo screens (`strikethrough`, 37 each) and at least 15 pickup-ETA +
  trip-time screens (`eta_trip_time`, 25 each). Trip time appears as "23 min trip" or "Drop 7:05 pm" on the
  same row as the pickup ETA.
- **Price formats:**
  - fare ranges (₹180–210),
  - Devanagari digits with Hindi labels (ऑटो, बाइक, कैब),
  - Indian grouping (₹1,040),
  - lakh grouping (₹1,23,456). Lakh rows are out of band, labelled `"expect": "UNREADABLE"` and tagged
    `out_of_band`,
  - the `Rs.` prefix.
- **Layouts:**
  - dark mode,
  - long labels truncated with "…",
  - 12-option lists,
  - struck price left of or below the payable price,
  - ETA under the label or under the price.
- **Image damage:** JPEG (quality 55–85), Gaussian blur, rotation (±0.4–1.2°, with OCR boxes and row boxes
  rotated to match) and seeded grain.
- **Injection:** instructions planted in a banner or a row label (`injection`, `injected_text`).

The generator's self-check runs the reference gate over every screen and exits 1 if a label, OCR file and
Channel A file disagree. The generator's run on 28 Oct 2026 wrote 165 screens (~80 MB) with no self-check
failures. That output was then deleted.
