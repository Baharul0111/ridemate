# Day-1 spikes (architecture §8, items 4–5)

Run with `tools/spikes/day1_spikes.py` through the backend router, so every call was metered (Purpose.PROBE). No fare values are recorded here.

```json
{
 "model_present": true,
 "read": {
  "mode": "json_schema",
  "latency_ms": 6898,
  "cost_usd": 0.00699,
  "attempts": 1,
  "screen_type": "fare_options",
  "options": 3,
  "rows_labelled": 3
 },
 "point": {
  "screens": 3,
  "hits": 3,
  "rows": [
   {
    "screen": "kestrel/cancel_flow.png",
    "hit": true,
    "latency_ms": 9389
   },
   {
    "screen": "pepper/cancel_flow.png",
    "hit": true,
    "latency_ms": 5968
   },
   {
    "screen": "tamarind/cancel_flow.png",
    "hit": true,
    "latency_ms": 5743
   }
  ],
  "supported": true
 },
 "burst": {
  "calls": 30,
  "outcomes": {
   "model_timeout": 30
  },
  "p50_ms": null,
  "p95_ms": null
 },
 "total_spike_usd": 0.0642
}
```

## Diagnosis and decisions (28 Oct 2026)

* **Structured output:** `json_schema` (strict) is accepted for image reads — pinned as the default mode.
* **Grounding ("Show me"):** 3/3 demo cancel screens put the box centre on the Cancel button (±4 %) →
  `point_supported = true`.
* **Reasoning cannot be switched off.** The endpoint accepts `reasoning_effort` ∈ {low, medium, high, xhigh} only
  (`none`/`minimal` → 400; `extra_body` switches are ignored). Reasoning tokens count against `max_tokens`:
  a one-line notification classification at `low` used ≈ 400–650 completion tokens and took 5.4–7.7 s. With the
  original 150-token ceiling the answer never arrived (`finish_reason=length`), which is why the 30-call burst
  showed 30 × `model_timeout`: it measured the ceiling, not a rate limit.
* **Decision (architecture changelog, "day-1 spike"):** output-token ceilings now include reasoning headroom —
  read 2000, reread 1600, point 700, parse 700, place-pick 600 — and server deadlines become read 12 s (unchanged),
  point 12 s, parse 10 s, place-pick 10 s (phone = server + 5 s). The classifier fallback (UNKNOWN_UPDATE → MAYBE
  prompt) still fires at the deadline, so "never silent" holds with a 3 s + 10 s bound. The burst test is re-run
  with the new limits.
* **Cost:** one demo fare-screen read ≈ $0.007; a classification ≈ $0.002–0.003.

## Client-IP header (per-network limits)

**Status: not yet verified against a real Modal deployment.** The day-1 run above recorded the model and endpoint
spikes only; no result was recorded for which forwarding header Modal's proxy sets. `limits.py` assumes Modal appends
the address it saw as the **right-most** `X-Forwarded-For` hop (hops to its left are client-supplied and never
trusted). If that is wrong, or changes later, the per-IP bucket silently skips every request.

What now guards that assumption, so it can't fail silently:
* every request is counted as `ip_bucket{outcome=bucketed|unbucketed|store_down, scope=v1|other}`, and the dispatcher
  alerts `ip_unbucketed_rate` when more than 20% of `/v1/*` requests in an hour (≥ 20 requests) had no usable hop;
* `/readyz` reports `client_bucketed` (a bool, never the address) for the request itself, and `deploy.sh` step (f)
  fails the deploy when the new deployment's own `/readyz` says `false`.

To record the result (first `./backend/deploy.sh --env dev`): step (f) prints whether this request got a bucket. If it
fails, look at the request headers Modal forwards (metadata only, never logged with values in production), switch the
header name in `IpLimiter` (its `header=` parameter), redeploy, and write the header and its hop structure here.
