# Runbook: restore the golden set

The **golden set** is the owner's labelled, masked screens and notification recordings, captured with the in-app
Recorder on Baharul's own phone (≥ 150 screens at submission, 300 later, including ≥ 15 strikethrough/promo and
≥ 15 ETA + trip-time screens per app). It powers the accuracy tests, the Template Doctor and the weekly drift run. It is
**never** in the public repo.

| Copy | Where | Role |
|---|---|---|
| Primary | Private GitHub repo `Baharul0111/ridemate-golden` | Versioned source of truth |
| Working copy | Private Modal Volume (`ridemate-golden`; exact name in `backend/modal_app.py`) | Live eval runs on Modal |
| Backup | Encrypted offline copy (external drive or password-manager file attachment) | Disaster recovery |

**Recovery target (RPO): 1 week.** At most one week of new recordings may be lost, so back up at least weekly.
**The restore must be tested once** (and after any big change); record the date below.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- The eval harness or weekly drift run fails with "file not found" or hash mismatches.
- The Modal Volume is empty or was deleted; the private repo is damaged or deleted; the laptop copy is lost.
- It's time for the restore test.

## Checks

```bash
modal volume ls ridemate-golden / --env main          # what's on the Modal Volume
git ls-remote https://github.com/Baharul0111/ridemate-golden.git   # is the private repo reachable (needs your login)
```

## Weekly backup (prevention)

```bash
cd ~/ridemate-golden                                   # your local clone of the private repo
git add -A && git commit -m "Recordings $(date +%F)" && git push
modal volume put ridemate-golden . / --force --env main
# Encrypted offline copy (you'll be asked for a passphrase; keep it in your password manager)
tar -czf - --exclude .git . | gpg --symmetric --cipher-algo AES256 -o "/Volumes/<drive>/golden-$(date +%F).tgz.gpg"
shasum -a 256 labels.json > /Volumes/<drive>/golden-$(date +%F).labels.sha256
```

## Fix: restore

### From the private GitHub repo (normal case)

```bash
git clone https://github.com/Baharul0111/ridemate-golden.git ~/ridemate-golden
cd ~/ridemate-golden
python3 -m json.tool labels.json > /dev/null && echo "labels.json OK"
modal volume put ridemate-golden . / --force --env main
modal volume ls ridemate-golden / --env main
```

### From the encrypted offline copy (repo lost too)

```bash
mkdir -p ~/ridemate-golden-restore && cd ~/ridemate-golden-restore
gpg --decrypt "/Volumes/<drive>/golden-<date>.tgz.gpg" | tar -xzf -
shasum -a 256 -c "/Volumes/<drive>/golden-<date>.labels.sha256"   # must print OK
```

Then re-create the private repo on GitHub (private!), push the restored files, and sync the Modal Volume as above.

### Verify the restore

- The number of screens matches the last known count, and every `sha256` in `labels.json` matches its file:

  ```bash
  python3 - <<'PY'
  import hashlib, json, pathlib
  labels = json.load(open("labels.json"))["screens"]
  bad = [s["file"] for s in labels
         if hashlib.sha256(pathlib.Path(s["file"]).read_bytes()).hexdigest() != s["sha256"]]
  print(len(labels), "screens;", len(bad), "mismatched", bad[:5])
  PY
  ```

- Run the eval harness once against the restored Volume (within the evals sub-budget, ≤ $0.50) and compare with the
  last recorded results.

## Rollback

Nothing to roll back; keep the damaged copy aside until the restore is verified, then delete it.

## Who to tell

Nobody outside. The golden set is owner data and contains no users' data.

## Restore test log

| Date | From | Result | By |
|---|---|---|---|
| _not yet tested_ | | | Baharul |
