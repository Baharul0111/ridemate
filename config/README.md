# Remote Config defaults

Every Remote Config key in `docs/contracts.md` §2 lives here as a default, a JSON Schema and a registry entry.
The phone ships these defaults inside the APK. The owner updates the live values remotely, within minutes and
without an app update (goal item 22, architecture §4.2 "Config").

| Path | What it is |
|---|---|
| `remote_config/<key>.json` | The default value of every key (22 keys) |
| `schemas/<key>.schema.json` | JSON Schema (draft 2020-12) for that file. Strings marked `"x-re2"` are RE2 patterns |
| `ridemate_config/keys.py` | The key registry: value type, description, bundled override |
| `validate.py` | Checks every default against its schema, compiles every regex with RE2, and checks that the bundled copies are current |
| `build_template.py` | Builds the Firebase REST template and the APK-bundled defaults |
| `tests/` | pytest suite, run from `eval/` (see below) |

## How defaults are stored

There is one file per key.
- **JSON keys** (`valueType: JSON`) hold the payload itself. Every object payload carries
  `"schema": "MAJOR.MINOR"`. `owner_emails_sha256` is a bare JSON list, as the contract specifies, so it has no
  `schema` field.
- **Scalar keys** (BOOLEAN, NUMBER, STRING) hold `{"value": <scalar>}`. Keeping one file per key means every key
  gets its own schema and a one-line diff when it changes. It also lets the registry, the files and the schemas
  be checked one-to-one.

Per-app payloads (`notification_templates`, `fare_parsers`, `cancel_cards`, `fee_rules`, `launch_templates`) are
keyed by Android package. Each one covers all six packages, except `launch_templates`, which covers the three
real apps only. The phone knows three launch modes (`uri`, `launch_paste`, `launch`) and rejects the whole
payload on any other, and DemoSource shows the demo providers' screens itself (contracts §5).
- **`demo.kestrel`, `demo.pepper`, `demo.tamarind`**: the fictional demo providers. They match the bundled demo
  screens and `android/app/src/main/assets/demo/script.json` exactly, and `eval/tests` checks that.
- **`com.ubercab`, `com.olacabs.customer`, `com.rapido.passenger`**: best-effort first versions, written in
  English and Hindi from general knowledge. The top-level `notes` field marks each one "unverified until
  recorded with the owner's Recorder". Nobody has looked at, scripted or automated those apps.
  - The patterns are deliberately conservative. A miss becomes UNKNOWN_UPDATE, which shows a MAYBE prompt.
  - `CANCELLED` templates only match when the title itself starts with the cancel phrase. That way a driver's
    chat message such as "Ride cancelled" never counts as rung-2 cancel proof.
  - `redacted` and `ongoing_channel_ids` stay empty until the real channel ids are recorded.

## Matching rules the phone must follow (Kotlin TemplateEngine / FareParser)
- The newest entry with `min_version_code` ≤ the installed versionCode is used. An unknown versionCode falls
  back to the newest entry below it (contracts §2.1). `max_known_version_code` is optional.
- **Template precedence is list order.** The first template whose pattern finds a match in `title + "\n" + text`
  (and whose `channel_id`/`ongoing` conditions hold) decides the event. Specific events come first:
  driver-cancelled before cancelled, and arriving before assigned.
  `ridemate_config/matching.py` is the Python reference for these rules.
- `vehicle_labels` are also tried in list order. The first match decides the class.
- Patterns are RE2 syntax and use `(?P<name>…)` named groups: `eta`, `dist`, `plate` and `name`.
  - `validate.py` also rejects `(?<name>…)` and `\C`, which RE2/J may not accept.
  - `\d` and `\b` are ASCII-only in RE2. That is why the Devanagari digit ranges (`०-९`) appear explicitly.
- `eta_pattern` group 1 holds the minutes. `driver_distance_pattern` group 1 holds the number and group 2 the
  unit.
- ₹ OCR confusions (Z, R, % or 2 before digits) are normalised by the parser before `price_token_pattern` runs
  (architecture M5). The pattern itself only accepts ₹, Rs and INR.

## Commands
Both scripts run in the eval uv project. It already has `jsonschema` and `google-re2`:

```sh
uv run --project eval python config/validate.py
uv run --project eval python config/build_template.py --bundled-only          # after editing a default
uv run --project eval python config/build_template.py \
    --api-base-url https://<workspace>--ridemate-api.modal.run [--out FILE] [--owner-email-sha256 HEX ...]
cd eval && uv run pytest -q                                                   # eval + config tests
```

`build_template.py` validates everything first. If any default is invalid, it writes nothing. Otherwise it writes:
- `config/remoteconfig.template.json`: the Firebase Remote Config REST template,
  `{"parameters": {key: {"defaultValue": {"value": "<string>"}, "valueType": …, "description": …}}}`, with
  `api_base_url` set. It is gitignored and regenerated at deploy, then published with
  `firebase deploy --only remoteconfig`.
- `android/app/src/main/assets/config/<key>.json`: the bundled payload of every JSON key, i.e. the "bundled"
  layer of `resolveLayered`.
- `android/app/src/main/res/xml/remote_config_defaults.xml`: every key, for
  `FirebaseRemoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)`. In this file and in the APK assets,
  `api_base_url` is empty. The real URL only ever arrives through Remote Config (contracts §1).

A test fails if the bundled copies are out of date. Never edit them by hand.

## Changing a payload
- **Additive changes** (a new optional field): bump MINOR (`1.0` → `1.1`) and update the schema.
- **Breaking changes**: ship under a new key, such as `fare_parsers_v2` (architecture F5). Add the key to
  `keys.py`, contracts §2 and a schema. Keep the old key served until `min_supported_version` passes it.
- **New real-app templates**: write them from the owner's Recorder exports. Each needs a replay sample in
  `tests/test_patterns.py`. Only then change that app's note to start with
  `verified against Recorder export <date>`. Until then the validator requires the "unverified" wording.
