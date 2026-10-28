# Runbook: the Muse Spark Open model was removed or renamed

RideMate pins one model id: **`meta-models/Muse-Spark-Open-816B-A42B`** on Modal's shared endpoint. The server checks
it with `GET /v1/models` at startup and in the daily probe. If it disappears, AI **switches off automatically**
(manual mode, guardian still on) instead of crashing, and an alert fires. A model change is never automatic: a new
id must pass the evaluation harness first.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- Alert **probe failure** or "model missing" (healthchecks.io fail email and FCM push).
- `/v1/config-check` reason `model_missing`; `/v1/read-screen` returns `503 ai_unavailable`.
- Logs show 404 / "model not found" from the endpoint.
- Modal announces a change to the Muse Spark Open library page or the shared endpoint.

## Checks

1. Is the model still listed? Use your **laptop** proxy token (listing costs nothing):

   ```bash
   curl -fsS -m 10 -H "Authorization: Bearer $MODAL_LAPTOP_TOKEN" "$MODAL_SPARK_OPEN_URL/v1/models" | python3 -m json.tool
   ```

2. Check <https://modal.com/library/meta/muse-spark-open> and the **Endpoints** page in the Modal dashboard: was the
   endpoint URL changed, the shared endpoint retired, or a new version published?
3. Is it really the model, or the endpoint/credit? If `/v1/models` fails entirely, see
   [modal-down-or-credit-used-up.md](modal-down-or-credit-used-up.md).

## Fix

1. **Nothing urgent for users:** they're already in manual mode and the demo falls back to recorded outputs. Optionally
   set `ai_reader_enabled` = `false` so the app shows a clean message.
2. **Only the endpoint URL changed:** update `MODAL_SPARK_OPEN_URL` in `.env` and `ridemate-model`, redeploy, check
   `/readyz` and one **live** fare read on your own phone (a cached demo screen never reaches the model).
3. **A new Muse Spark Open id is offered** (for example a new revision):
   - Run the eval harness in `eval/` against the golden set with the new id, within the evals sub-budget
     (≤ $0.50 per run). Compare with the recorded baseline: verified rate, wrong-shown (must be 0 on the golden set),
     latency (read p95 ≤ 8 s on the server) and tokens per read (cost).
   - Run the day-1 checks for the new id: vision works, which structured-output mode it accepts, grounding support.
   - If it passes: change the pinned id in `backend/ridemate_api/settings.py` (`PINNED_MODEL_ID`) through a reviewed
     commit, update `model_route` if needed, deploy, then re-enable AI. Re-record `eval/recorded/channel_a/` outputs for
     the demo and the accuracy test.
   - If it fails: stay in manual mode and keep looking.
4. **No Meta open-weights model available on Modal any more:** stay in manual mode. Adding another provider is
   possible through `model_route` without app changes, but anything that costs money outside the Modal credit is a
   decision for later, not an incident step.

## Rollback

If the new model behaves worse in real use, revert the commit that changed `PINNED_MODEL_ID` and redeploy
([rollback.md](rollback.md)), or switch AI off again.

## Who to tell

- **Users:** README status line: "AI reading is paused while we move to the new model version; type prices for now."
- **Hackathon organisers** (during judging): the live AI is paused; Try the demo still runs on recorded Muse Spark
  Open outputs.
- **Modal support:** ask about the model's status and any migration path.
