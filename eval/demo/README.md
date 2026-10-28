# Try the demo: fictional providers, screens and script

Goal item 19 asks for a demo that anyone can try. It shows reading prices, a driver being found, the cancel
guide with proof and reminders, and a better-ride offer. It needs no ride app, no sign-in and no AI budget.
Everything here is **fictional**:
- Three invented brands: Kestrel Go, Pepper Rides and Tamarind Cabs.
- Plain shapes and invented colours, so no screen looks like a real app.
- Every screen has a fake status bar and a "DEMO · fictional provider" banner.
- Driver names, plates (`XX 00 DM ####`), OTP/PIN codes and the phone number (`+91 98765 43210`, the usual
  Indian placeholder) are fake. They exist so the PII masker has something to find.

```sh
cd eval && uv run python demo/generate_demo.py           # regenerate everything below
cd eval && uv run python demo/generate_demo.py --check   # CI: exit 1 if anything differs
```

The generator is deterministic. Two runs produce identical bytes with the Pillow version in `eval/uv.lock`, and
`tests/test_demo_assets.py` checks this. Changing a screen changes its SHA-256. If that happens:
1. Regenerate, then commit the PNGs, `eval/demo_labels.json` and `eval/synthetic/` together.
2. Tell the backend so its demo allowlist (SHA-256 of the bundled images) follows.
3. Re-record `eval/recorded/channel_a/` for the changed screens.

## Providers and screens (`android/app/src/main/assets/demo/<provider>/`)

| Provider | Package / wire id | Vehicles | Fare screen covers |
|---|---|---|---|
| Kestrel Go | `demo.kestrel` / `demo_kestrel` | bike, auto, cab_economy | struck promo price (Bike ₹112, was ₹135), **range** (Auto ₹180–210) |
| Pepper Rides | `demo.pepper` / `demo_pepper` | auto, cab_economy, cab_premium | **Hindi label + Devanagari digits** (ऑटो ₹१७२, ३ min), struck price (Pepper Go ₹264, was ₹299) |
| Tamarind Cabs | `demo.tamarind` / `demo_tamarind` | bike, auto, cab_economy, cab_xl | struck price (Auto ₹176, was ₹205), **Indian grouping** (Tamarind XL ₹1,040) |

Each provider has five screens, plus one extra for Pepper (16 PNGs, 1080×2400):

| Screen | Shows |
|---|---|
| `fare_options.png` | 3–4 rows, each with a label, the payable price, the pickup ETA "N min" and a tagline. The booked row is highlighted |
| `searching.png` | "Finding a rider…" / "Searching for nearby drivers…" / "Looking for a driver…" |
| `driver_assigned.png` | "N min away", "X.Y km from pickup", a plate, OTP/PIN and phone (Kestrel 4 min · 1.6 km, Pepper 9 min · 2.8 km, Tamarind 2 min · 0.6 km) |
| `cancel_flow.png` | "Cancel your ride?" with reasons, a fee note and a large **Cancel ride** button. Its box is the label's `targets.cancel_button`, used by Show me |
| `cancelled.png` | "Trip cancelled" / "Booking cancelled" / "Ride cancelled" (rung-3 keywords) |
| `pepper/driver_assigned_better.png` | Pepper re-matched with a much closer driver (Imran, 3 min · 0.5 km, `XX 00 DM 7305`) |

The trip is Riverside Gate (26.14450, 91.73620) to Airport Terminal (26.11120, 91.63442): 10.81 km in a
straight line, 14.6 km by road (haversine × `road_factor` 1.35). Every demo price sits inside the default sanity
bands for that distance.

`trip_km` in the labels and in `script.json` is the **straight-line** (haversine) distance, because that is what
the phone's gate receives as `tripKm`: core `SanityBands` multiplies it by `road_factor` itself (contracts §2.3).
A road distance in `trip_km` would be multiplied twice and widen the bands.

The ground truth is `eval/demo_labels.json` (contracts §6). On top of the contract fields it adds:
- `bbox_0_1000` per row,
- `fee_text`,
- `targets` (Show me boxes),
- `tags`.

## `script.json` (DemoSource timeline)

`android/app/src/main/assets/demo/script.json`, schema `eval/schemas/demo_script.schema.json`.

```json
{"schema": "1.0",
 "trip": {"pickup": {"label", "lat", "lng"}, "drop": {...}, "trip_km": 10.81},
 "providers": {"demo.pepper": {"name", "wire_id", "version_code": 1,
                               "screens": {"fare_options": "demo/pepper/fare_options.png", ...},
                               "cancel_flow_from": "driver_assigned",
                               "booked": {"vehicle_class", "label", "price_min", "price_max"}}},
 "user_steps": {"book:<package>": "…", ...},
 "tracks": [{"id": "main", "description": "…", "requires": {"keep_searching": false},
             "events": [ …event… ], "checkpoints": [ …expected guardian behaviour… ]}]}
```

### Event fields

| Field | Meaning |
|---|---|
| `t_ms` | Milliseconds after the anchor. The anchor is the moment the user completes `requires_user_step` |
| `requires_user_step` | The user action that starts this event's clock (see below). Every event has one |
| `action` | `post` (a notification is posted or updated) or `remove` (the provider removes it) |
| `package` | `demo.kestrel` / `demo.pepper` / `demo.tamarind` |
| `sbn_key` | Android-style `StatusBarNotification` key. The ongoing ride post keeps one key and is updated in place. One-shot alerts (cancelled, ended) use a second key |
| `channel_id` | `kestrel_trip` / `pepper_ride_status` / `tamarind_live` for ongoing posts. `*_alerts` / `*_updates` / `*_info` for alerts |
| `category` | `"status"` for ongoing posts, `null` for alerts |
| `ongoing` | `Notification.FLAG_ONGOING_EVENT` |
| `title`, `text` | Exactly what the post carries. The demo templates match `title + "\n" + text` |
| `removal_reason` | Only on `remove`. Always `REASON_APP_CANCEL` (NotificationListenerService constant 8) |
| `expect_event` | Only on `post`. The template event this post must produce |
| `expect_groups` | On ASSIGNED posts: the named groups `plate`, `eta` and `dist` that the template must extract |
| `screen` | The key in `providers[package].screens` that the "app" shows from now on (DemoSource shows it when RideMate opens that provider) |

Events that share an anchor are listed in firing order. Events with different anchors are independent.

### User steps

| Step | Meaning |
|---|---|
| `book:<package>` | The user taps "Booked here" after reading that provider's fare screen. DemoSource walks Kestrel, then Pepper, then Tamarind |
| `cancel_others:yes` | The user taps "Yes, cancel the others" on the first-driver sheet |
| `cancel_others:not_now` | The user keeps searching instead. Only offered when *Keep searching* is on |
| `tapped_cancel:<package>` | On that provider's `cancel_flow.png`, the user taps **Cancel ride** (or RideMate's "I tapped Cancel"). In demo mode this stands in for the tap inside the ride app. The provider then proves the cancel: an exact `CANCELLED` template post (rung 2), followed by removal of the ongoing post with `REASON_APP_CANCEL` (rung 3) |
| `better_ride:yes` | The user accepts the better-ride offer |

### Tracks
- **`main`** (keep searching off):
  1. All three providers are booked and post SEARCHING.
  2. Pepper is ASSIGNED 9 s after the last booking: Ravi, 9 min, 2.8 km.
  3. The first-driver sheet appears, and the user taps "Yes, cancel the others".
  4. The chain runs Kestrel, then Tamarind (both searching).
  5. Each cancel is proven 1.5 s after the user's tap.
  6. Reminders are expected at 30, 60, 120 and 240 s while any leg is pending (see `checkpoints`).
  7. Pepper arrives, the trip starts, and it ends. ENDED leads to the pay hand-off in Pepper Rides.
- **`keep_searching`** (keep searching on):
  1. The track starts like `main`, but the user answers "not now".
  2. Tamarind is ASSIGNED 8 s later: Sunil, 2 min, 0.6 km. That is a 7 min gain, at least max(5, 40% × 9), so a
     better-ride offer appears.
  3. The user taps Yes. The chain runs Kestrel (searching), then Pepper (old leg). Both are proven.
  4. Tamarind takes the trip to payment.
- **`rematch`** (Pepper only):
  1. Ravi is assigned.
  2. DRIVER_CANCELLED.
  3. Imran is assigned with a new plate, so a new generation starts (`driver_assigned_better.png`).
  4. Arriving, started, ended.

### Exact demo notification texts (they must match `notification_templates` `demo.*`)

| Package | Event | Title | Text |
|---|---|---|---|
| demo.kestrel | SEARCHING | Kestrel Go | Finding a rider for your Auto… |
| demo.kestrel | ASSIGNED | Rider on the way | Arjun · XX 00 DM 1001 · 4 min away · 1.6 km |
| demo.kestrel | ARRIVING | Rider has arrived | Arjun is waiting at Riverside Gate. Share OTP 4719 to start. |
| demo.kestrel | DRIVER_CANCELLED | Rider cancelled | Sorry, your rider had to cancel. We're finding you another rider. |
| demo.kestrel | NO_DRIVERS | No riders nearby | We couldn't find a rider for this trip. Please try again. |
| demo.kestrel | CANCELLED | Trip cancelled | Your Kestrel Go trip was cancelled. No fee charged. |
| demo.kestrel | TRIP_STARTED | Trip started | Enjoy your ride with Kestrel Go. |
| demo.kestrel | ENDED | Trip completed | You have arrived. Pay ₹180 in Kestrel Go. |
| demo.pepper | SEARCHING | Pepper Rides | Searching for nearby drivers… |
| demo.pepper | ASSIGNED | Driver assigned | Ravi · XX 00 DM 4821 · arriving in 9 min · 2.8 km away |
| demo.pepper | ASSIGNED (rematch) | Driver assigned | Imran · XX 00 DM 7305 · arriving in 3 min · 0.5 km away |
| demo.pepper | ARRIVING | Driver has arrived | Ravi is at your pickup point. Your PIN is 5302. |
| demo.pepper | DRIVER_CANCELLED | Driver cancelled | Your driver cancelled. Finding you a new driver… |
| demo.pepper | NO_DRIVERS | No drivers found | All drivers are busy right now. Please try again. |
| demo.pepper | CANCELLED | Booking cancelled | Your Pepper Rides booking is cancelled. No charge applied. |
| demo.pepper | TRIP_STARTED | Ride started | Have a safe ride! |
| demo.pepper | ENDED | Ride completed | Please pay ₹172 in Pepper Rides. |
| demo.tamarind | SEARCHING | Tamarind Cabs | ड्राइवर खोज रहे हैं · Looking for a driver… |
| demo.tamarind | ASSIGNED | ड्राइवर मिल गया · Driver found | Sunil · XX 00 DM 2290 · 2 मिनट दूर · 0.6 km |
| demo.tamarind | ARRIVING | ड्राइवर पहुँच गया · Driver arrived | Your Tamarind cab is at the pickup point. |
| demo.tamarind | DRIVER_CANCELLED | Driver cancelled | ड्राइवर ने राइड रद्द की। Finding another driver… |
| demo.tamarind | NO_DRIVERS | No cabs available | अभी कोई कैब उपलब्ध नहीं है। Please try again later. |
| demo.tamarind | CANCELLED | राइड रद्द · Ride cancelled | Your Tamarind Cabs booking was cancelled. |
| demo.tamarind | TRIP_STARTED | यात्रा शुरू · Trip started | Have a good trip with Tamarind Cabs. |
| demo.tamarind | ENDED | Trip ended | Please pay ₹176 in Tamarind Cabs. |

The demo templates are anchored on the title (`^Trip cancelled\n…`). A chat-style post that repeats the
cancel words under another title (`Message from Ravi`) never counts as proof. See `eval/injection/`.

## Notes for DemoSource (Kotlin)
- **Never mix with live legs.** Demo posts go straight into the RideWatch pipeline with `package = demo.*`.
  They never pass through the NotificationListenerService allowlist (I7 stays three packages).
- **Upload the bundled PNG bytes unchanged.** `/v1/read-screen` recognises demo images by the SHA-256 of the
  exact uploaded bytes (contracts §1.3/§6). If DemoSource re-encodes or masks a demo frame, its hash no
  longer matches the allowlist. The demo screens contain only fake PII, so the masker may run for show, but
  the uploaded bytes must be the original asset.
- The demo versionCode is `1`, and all demo templates are `min_version_code: 0`.
