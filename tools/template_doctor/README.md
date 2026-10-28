# Template Doctor

An owner tool (architecture §4.2 "Developer tooling", §4.10). When a ride app changes its notification wording or
its price screens, the Doctor reads **only the owner's masked recordings**, finds where RideMate's rules miss, asks
Muse Spark Open (Meta open weights, on Modal) to propose fixes, checks every proposal itself, and writes a bundle a
human reviews. It never sees user data, never acts in any ride app, and never pushes by itself.

## What it reads

| Input | Flag | Format |
|---|---|---|
| Recorder exports (masked notification posts, §4.19(a)) | `--recorder FILE_OR_GLOB` (repeatable) | `{"schema": "1.0", "posts": [...]}` or a bare list; see `doctorlib/samples.py` for the fields (snake_case or camelCase). `label` is the owner's optional truth: an event (`ASSIGNED`, …) or `OTHER` for a post that must match nothing |
| Golden captures (§4.19(c), contracts §6) | `--labels labels.json` + `--ocr-dir DIR` (repeatable, priority order; default `<labels dir>/ocr`, then `recorded/ocr`) | `labels.json` screens + `<sha256>.json` OCR token files |
| Baseline rules | `--config-dir` (default `config/remote_config/`) | `notification_templates.json`, `fare_parsers.json` |

Text is masked on the phone; the Doctor scrubs it again (phone numbers, OTPs, number plates) before matching or
showing it to the model. Mask tokens `<name>`, `<phone>`, `<otp>`, `<plate>` are matched as neutral stand-ins, and
a proposed pattern that contains a mask token literally is rejected.

## What it does

1. **Finds misses.** Notification posts are matched exactly like the phone does (`ridemate_config.matching`
   rules, google-re2 with the RE2/J portability checks): `unmatched`, `wrong_event`, `false_match` (an `OTHER`
   post a template catches) and `redacted_unmatched`. Golden screens are parsed with a Python port of the Channel B
   FareParser (`doctorlib/fare_rules.py`) and compared with the labels: `row_missing`, `price_mismatch`,
   `eta_mismatch`, `extra_row`, `screen_type`.
2. **Asks the model**, in a tool-calling loop (≤ 16 turns), with five tools: `list_misses`, `read_sample`,
   `propose_template`, `propose_parser_change`, `run_regression`.
3. **Checks every proposal** before staging it: compiles under RE2 and is RE2/J-portable, only allowed named groups,
   does not match the empty string, matches every positive sample, matches **no negative sample** (named ones plus
   every recorded post of that app whose expected event differs), and the regression gate stays green.
4. **Regression gate:** every post the baseline classified correctly still is; every golden screen the baseline
   parser agreed with still agrees; the staged JSON passes the schema + RE2 validation; and for the final bundle,
   `config/build_template.py --bundled-only` and `config/validate.py` exit 0 on a scratch copy holding the patch.
5. **Writes a bundle** to `tools/template_doctor/out/<timestamp>/`: the patched `notification_templates.json` /
   `fare_parsers.json` (only if the final gate passed), `patch.diff`, `report.md` (sample ids, reason codes and
   patterns only: no notification text, no fares) and `proposal.json`.

New ride-app versions get their own entry (`min_version_code` = the new versionCode, copied from the entry that
served it), so older app versions keep their rules. Template ids are unique across the payload, so the copied
templates are renamed `<id>_vc<versionCode>`; `before_template_id` may still name the original id.

The model's free-text rationale is recorded with quoted spans and every number removed, so `report.md` (the PR
body) never carries notification text or fares even if the model quotes a sample.

## Money

Every model call is reserved in the backend's all-spend meter under `Purpose.DOCTOR` (request ids
`eval-run:doctor-<run>:…`, so the meter's per-eval-run cap applies too) and settled with the real token usage. On
top of that the run has a **hard cap of $0.50**: each turn's reservation is a true upper bound (every input byte
priced as a token plus every allowed output token, scaled by the meter's billing drift factor exactly as the meter
reserves it), and a turn that could cross the cap is never sent. The run
**refuses to start** when the evals sub-budget ($5 total) is exhausted, when the meter is unreadable, or when the
meter refuses the first turn.

## Run it

```bash
# No model, no spend: just the misses.
uv run --project tools/template_doctor python tools/template_doctor/doctor.py \
  --recorder ~/ridemate-golden/recorder/*.json --labels ~/ridemate-golden/labels.json --dry-run

# With the model (needs MODAL_SPARK_OPEN_URL + your laptop proxy token, and Modal access to the hot Dict).
uv run --project tools/template_doctor python tools/template_doctor/doctor.py \
  --recorder ~/ridemate-golden/recorder/*.json --labels ~/ridemate-golden/labels.json

# Turn an accepted bundle into a PR on the repository you choose.
...doctor.py ... --open-pr --repo OWNER/NAME              # prints the commands only (default)
...doctor.py ... --open-pr --repo OWNER/NAME --execute    # creates the branch + local commit, prints push/PR
...doctor.py ... --open-pr --repo OWNER/NAME --execute --push   # also pushes and runs gh pr create
```

`--push` refuses to run anything unless the push remote (`--remote`, default `origin`) points at `--repo` on
GitHub, so the branch and the pull request always land in the repository you chose.

Then follow [docs/runbooks/ride-app-update-breaks-reading.md](../../docs/runbooks/ride-app-update-breaks-reading.md):
review, `./gradlew :core:test` (the Kotlin parser and template engine are authoritative), `ridemate-dev`, then a
10% → 100% Remote Config rollout.

## Weekly drift run

`run_report(deps, budget_usd)` (also `from tools.template_doctor import run_report`, and the keyword form
`run_report(router=…, spend=…, max_usd=…, purpose=Purpose.DOCTOR)` used by `backend/ridemate_api/cron.py`) runs in
report mode: same checks, a bundle under `$TEMPLATE_DOCTOR_OUT_DIR` (default `<golden>/doctor_reports`), never a PR.
Inputs come from the golden Volume at `$TEMPLATE_DOCTOR_GOLDEN_DIR` (default `/golden`: `labels.json`, `ocr/` or
`recorded/ocr/`, `recorder/*.json`); the baseline rules are the live Remote Config values.

## Tests

```bash
uv run --project tools/template_doctor pytest tools/template_doctor/tests
```

They use a fake model and never call the endpoint.
