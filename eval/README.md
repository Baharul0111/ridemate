# eval/: demo assets, fixtures and the accuracy harness

All tooling here is Python 3.12 in its own uv project (`pyproject.toml`, `uv.lock`). Nothing in this folder
calls a model or spends money, except `harness/record_channel_a.py`. That script only runs when the owner
starts it, and it is always metered (see `harness/README.md`).

```sh
cd eval
uv run pytest -q                              # eval tests + config/ tests (no network, no spend)
uv run python demo/generate_demo.py           # demo screens, labels, synthetic fixtures, script.json
uv run python injection/generate_injection.py # prompt-injection suite
uv run python golden/generate_golden.py       # ≥150 golden screens → eval/private/golden (gitignored)
```

| Path | Committed | What |
|---|---|---|
| `demo/` | yes | Demo generator + **script.json format** (`demo/README.md`) |
| `demo_labels.json` | yes | Ground truth for the 16 demo screens (contracts §6) |
| `synthetic/ocr/<sha>.json` | yes | Ideal OCR from the generator: one token per word, exact boxes, line/block ids |
| `synthetic/ocr_noisy/<sha>.<variant>.json` | yes | ML Kit-style misreads (₹ read as Z/R/%, merged `4min`, split price), each with `expect` |
| `synthetic/channel_a/<sha>.json` | yes | "Perfect reader" Channel A output (contracts §1.3 shape) with `expect` (all VERIFIED) |
| `synthetic/channel_a_adversarial/<sha>.<variant>.json` | yes | Reads the gate must reject: `one_digit_off`, `struck_as_payable`, `label_swapped`, `bbox_shifted`, `wrong_app`, `prompt_injection_label`, `driver_eta_off`, `driver_distance_off` |
| `injection/` | yes | Prompt-injection screens and notification cases (`injection/README.md`) |
| `golden/` | generator only | Golden-set generator (`golden/README.md`); its output is **never** committed |
| `private/` | **no** (gitignored) | Default golden output |
| `recorded/` | filled later | Real Muse Spark Open / ML Kit recordings (`recorded/README.md`) |
| `harness/` | yes | Record, summarise and sync scripts (`harness/README.md`) |
| `schemas/` | yes | JSON Schemas for labels, OCR, Channel A and script.json |
| `fonts/` | yes | Noto Sans + Mukta (OFL 1.1, `fonts/README.md`) |
| `ridemate_eval/` | yes | Shared library: renderer, providers, fixtures, golden plan, reference gate |

## `expect` outcomes
`VERIFIED` means the row shows exactly the labelled value. `UNREADABLE` means "Couldn't read · check in app"
with no number. `NOT_WRONG` means either of those two, never a different number. `SEE_IN_APP` means the driver
ETA or distance is hidden. Expectations assume the §4.3 re-read returns the same values as the first read.

`ridemate_eval/reference_gate.py` is a small Python reference of the §4.3 gate. It is used only to prove that
every fixture's `expect` block is self-consistent. The Kotlin VerificationGate is the real implementation, and
the Kotlin AccuracyTest should reach the same outcomes on these fixtures.
