# Eval harness

None of these scripts runs in CI against a live service. They make no model calls unless the owner runs
`record_channel_a.py` without `--dry-run`, and that path is always metered by the all-spend meter (§4.2).

## `record_channel_a.py`: record real Channel A outputs

It reads every screen in a labels file (default `eval/demo_labels.json`, or a golden `labels.json`). Each image
is checked against its labelled SHA-256. The result is written in the contracts §6 shape, plus `source`,
`request_id`, `latency_ms` and `wall_ms`:
- for the demo labels, into `eval/recorded/channel_a/<sha256>.json`;
- for any other labels file, by default into `recorded/channel_a/` next to that file. The script refuses to write
  reads of private screens (outside the repo, or gitignored) into any path git would track (L6).

Two timings, never mixed up:
- `latency_ms` is **server-side model time**: in gateway mode the router's own timing of the model call (the figure
  its 12 s deadline, the 20 s tap budget and the 15 s read-p95 alert are about); in http mode the API's `latency_ms`.
  It is left out when the server did not report one.
- `wall_ms` is this script's wall time for the whole call. From a laptop in gateway mode it includes the Modal Dict
  reservation and settlement round trips, so 45–60 s here is normal and says nothing about model speed.

The 16 demo recordings of 2026-10-28 (gateway mode) predate this split: their old `latency_ms` was wall time, so it
now sits in `wall_ms` and they carry no `latency_ms`. Judge read latency from `ai_model_latency_ms` metrics or
`docs/spikes.md` (6.9 s), not from those files.

| Mode | How | Metering |
|---|---|---|
| `http` (default) | `RIDEMATE_ID_TOKEN=<Firebase ID token> python eval/harness/record_channel_a.py --api-base-url https://…` | Server-side: every call goes through quotas and the spend meter. `cost_usd` is the before/after difference in `/v1/config-check` `budget.ai_spent_usd`, so it is available only for owner tokens. Otherwise it is `null` and counted at the worst case. `usage` is `null`: the API does not return token counts |
| `gateway` | `cd backend && uv run python ../eval/harness/record_channel_a.py --mode gateway` | In-process, through the backend's own `ProviderRouter` and production spend meter under `Purpose.EVAL`, with request ids `eval-run:<run id>:…`. The meter itself then enforces the evals sub-budget ($5) and the $0.50 per-run cap, retries included. The script refuses to start without `deps.spend`, refuses each call while `ai_status` is disabled, and **aborts** if `spend.summary()["ai_spent_usd"]` did not grow by the call's cost (meter bypass) or cannot be read. `usage` is `null`: the router returns only the settled cost |

Guards:
- `--max-usd` defaults to $0.50 and may not exceed it: that is the per-run eval cap (architecture §4.2). The
  $5 evals sub-budget is spread over runs; it is not a per-run allowance.
- Before each call the script checks that spent + worst case (≈ $0.0218: two attempts, because the router may
  retry once, of 6,000 input + 800 output tokens at $1.25 / $4.25 per 1M) stays under the cap. A call whose
  cost is unknown is counted at that worst case. In gateway mode a call is counted at the meter's growth, so a
  first attempt the router retried is counted too.
- An error envelope (`ai_paused`, `quota_exhausted`, `meter_unavailable`, auth …) stops the run. It is never
  retried.
- A response with `source: "recorded"` is not saved: it would be circular.
- Screens that already have a recording are skipped unless `--force` is given.
- `--dry-run` lists the screens and the worst-case spend. It needs no network and no token.
- The token is read from the environment only. It is never printed or written.

### Backend interface used by gateway mode
Everything comes from the backend package itself (contracts §3), so a read is built exactly as
`/v1/read-screen` builds it:

```python
deps = ridemate_api.deps_prod.build_production_deps()  # real Settings, Modal Dict meter, router
request = prompts.read_request(ImagePart(image, media_type))
result = await deps.router.call(
    request, task=TaskKind.READ, purpose=Purpose.EVAL, principal=None, request_id=f"eval-run:{run_id}:{uuid}"
)
read = prompts.validate_read(result.data)  # ChannelARead dict (contracts §1.3)
result.model, result.cost_usd  # pinned model id, USD settled on the meter
deps.spend.ai_status(None).enabled, deps.spend.summary()["ai_spent_usd"]
```

`tests/test_harness.py` covers the adapter with fakes of this exact shape. It was also run once against the real
`ProviderRouter` and `SpendMeter` (in-memory store, scripted provider, no network): the meter stopped an
unbounded run with `ai_paused` before the run's eval spend passed $0.50.

## `summarize.py`: accuracy summary without fare values (L6)

```sh
python eval/harness/summarize.py [--results android/core/build/accuracy] [--json] \
    [--max-wrong-rate 0.005] [--min-verified-rate 0.7]
```

The script reads every `*.json` in `android/core/build/accuracy/`, written by the Kotlin AccuracyTest. It
prints the screens, rows, VERIFIED rate, wrong-shown count, unreadable count and driver facts verified/wrong,
per suite and per app.
- It reads only enum outcomes and booleans, so no fare, ETA or label can reach the log.
- It exits 1 if any `demo` or `synthetic` suite shows a wrong value (target 0).
- It exits 1 if the wrong-shown rate exceeds 0.5% (SLO §4.12), or if the VERIFIED rate falls below
  `--min-verified-rate`.
- With no result files it prints a hint and exits 0.

**Result file format the Kotlin AccuracyTest must write** (one file per suite):

```json
{"schema": "1.0", "suite": "demo|synthetic|golden|recorded", "generated_at": "2026-10-28T10:00:00Z",
 "screens": [{"sha256": "…", "app": "demo_pepper", "screen_type": "fare_options",
              "rows": [{"outcome": "VERIFIED|UNREADABLE", "correct": true}],
              "driver_eta": {"outcome": "VERIFIED|SEE_IN_APP", "correct": true},
              "driver_distance": null}]}
```

Each row lines up with the labelled row at the same index.
- `correct` is `true` or `false` only when the outcome is VERIFIED. It records whether the shown value equals
  the label. Otherwise it is `null`.
- Do not put prices in this file. They would be ignored anyway.

## `sync_golden_to_volume.py`: golden set → Modal Volume `ridemate-golden` (owner only)

```sh
cd backend && uv run python ../eval/harness/sync_golden_to_volume.py --src ~/ridemate-golden [--dry-run]
```

The script uploads `labels.json`, `images/`, `ocr/` and `channel_a/` with `modal.Volume.batch_upload`. First it
checks that:
- every labelled image exists and matches its SHA-256,
- the source is not a tracked part of the public repo.

Storing files makes no model calls. Not run during the build.
