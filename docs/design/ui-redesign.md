# RideMate UI redesign — "one glance, one tap"

Goal: RideMate should feel like a modern ride app — calm, visual, almost wordless — not a settings app.
It must keep RideMate's own identity: **never copy Uber's, Ola's or Rapido's logos, colours, typefaces or screen
layouts** (trade dress). We borrow the *patterns* every good ride app shares, not their look.

## 1. Principles (from ride-app UX research)
1. **One question per screen.** Each step asks one thing ("Where to?", "Which apps?"). Choices appear only after
   the previous one is made (progressive disclosure).
2. **Bottom-sheet flow.** A bold header area on top, a rounded sheet below holding the current step; the primary
   action sits at the bottom in the thumb zone, full width, 56 dp tall.
3. **Price first.** On option cards the fare is the largest text, right-aligned; ETA is a small chip next to it.
4. **Icons before words.** Every row starts with an icon or provider avatar. Labels are 1–3 words. Explanations
   move behind an ⓘ "Why?" tap (bottom sheet), never as paragraphs on the main screen.
5. **Status at a glance.** State = coloured pill **with icon and word** (never colour alone): Searching ⏳,
   Driver coming 🚗, Cancelled ✓, Check app ⚠.
6. **Calm motion.** Content slides/fades between steps (≤ 250 ms), sheets spring up, prices count in once.
   Respect "remove animations" (Settings.Global animator scale 0 → no motion).

## 2. Visual system (Material 3, RideMate brand)
| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` (primary) | #0E6B57 deep teal | #6FD3B8 | primary buttons, selected state, links |
| `accent` (secondary) | #F2B33D marigold | #F5C25E | "Cheapest" badge, highlights — never a full background |
| `ink` (onSurface) | #121614 | #E6EAE7 | text |
| `muted` (onSurfaceVariant) | #5C6661 | #A9B4AF | secondary text |
| `canvas` (background) | #F5F7F6 | #0E1110 | screen background |
| `sheet` (surface) | #FFFFFF | #181C1A | cards, sheets |
| `line` (outlineVariant) | #E3E8E5 | #2A302D | dividers, card borders |
| `good` | #1E8E4E | #5FD08F | proven/cancelled-OK |
| `warn` | #B26A00 | #FFB74D | check in app / old price |
| `danger` (error) | #C62828 | #FF8A80 | loud alerts |

* **Type:** Material 3 type scale on the default sans (Roboto/Noto, Hindi falls back to Noto Sans Devanagari).
  Display 32 sp bold (step questions), Title 22 sp semibold, Price 24 sp bold tabular figures
  (`fontFeatureSettings = "tnum"`), Body 16 sp, Label 13 sp medium. Max 2 lines for any label.
* **Shape:** cards 20 dp, sheets 28 dp top corners, buttons/pills fully rounded, chips 12 dp.
* **Spacing:** 4-pt grid; screen padding 20 dp; card padding 16 dp; 12 dp between cards.
* **Elevation:** flat cards with 1 dp `line` border in light mode, tonal surfaces in dark; only sheets/FAB/bubble
  cast shadow.
* **Icons:** Material Symbols Rounded (material-icons-extended) + custom vectors for **bike, auto-rickshaw, cab,
  cab XL** (simple side-view glyphs, 24 dp grid, drawn in-house).
* **Provider avatars:** 40 dp circle, neutral tonal colour per provider with the provider's initial (U / O / R /
  N for ONDC, K / P / T for demo) — no real logos or brand colours. Demo providers carry a small "DEMO" corner tag.

## 3. Components (app.ridemate.ui.components)
`RmScaffold` (canvas + top area + bottom sheet slot + bottom action bar) · `WhereToBar` (big pill: search icon,
"Where to?", mic) · `RouteStack` (pickup dot ● / drop square ■ joined by a dotted line; tap a line to edit) ·
`PlaceRow` (pin icon, 1-line name, 1-line muted area) · `ProviderAvatar` · `VehicleIcon` · `ChoiceTile`
(icon + 1–2 word label, selectable, 48 dp+, checkmark when selected) · `FareCard` (avatar, vehicle label, ETA chip,
big price right, "Cheapest" accent badge, stale = clock icon + "1 min ago" + refresh icon button, unreadable =
dashed border + ⚠ "Check in app" with no number, entered-by-you = ✎ tag, network = 🌐 tag) · `StatusPill` ·
`LegTimeline` (vertical: avatar → state pill → evidence tick) · `BigPrimaryButton` / `QuietButton` ·
`InfoSheet` (ⓘ explanations, legal text) · `StepDots` (onboarding progress) · `HeroIllustration` (vector art for
onboarding/empty states, no stock images) · `Toast/InlineBanner` (offline, manual mode "AI back at 1:30 pm").
All components: `Modifier.semantics` content descriptions, `testTag`s kept stable for androidTests, ≥ 48 dp
targets, state conveyed by icon + text.

## 4. Screens
**Onboarding (6 swipe-free steps, StepDots on top):** each = HeroIllustration + 1 title (≤ 5 words) + 1 subtitle
(≤ 12 words) + one big button. 18+ → "Privacy, simply" (3 icon bullets + "Read full notice" sheet, keeps every
DPDP line) → "Sign in for AI reading" (Google button + "Skip — I'll type prices") → permissions as one checklist
screen: rows (icon, 2-word name, ✓/Allow button, ⓘ why-sheet) for Notifications, Ride alerts (listener), Bubble
(overlay), Location, Battery; the restricted-settings and OEM guides open as illustrated step sheets only when
needed → "All set" with the self-test result (✓ RideMate can see ride alerts).

**Home:** greeting-free. Top: `WhereToBar`. Below: recent places as 2–3 `PlaceRow`s. Active-ride card (if any)
pinned on top with LegTimeline summary. Two tiles: "Compare" (if a destination is chosen) and "Try demo" (DEMO tag).
AI status as a small InlineBanner only when not normal. Settings = avatar/gear icon top-right.

**Trip:** full-screen search with RouteStack at top, suggestions list, mic. After drop is confirmed → sheet
"Which apps?" (installed apps as ChoiceTiles with avatars) + "Ride type" (Bike / Auto / Cab ChoiceTiles with
VehicleIcons) + "Open network" toggle → BigPrimaryButton "Compare 3 apps". "View on map" is an icon button on the
drop line.

**Compare (the hero screen):** header = RouteStack compact. Body = sections per vehicle type (VehicleIcon +
"Auto"), FareCards sorted cheapest-first (verified only). Bottom bar: "Open next app" / "Read fares" depending on
state. Manual entry = small ✎ "Type price" in the card. Stale cards grey with clock icon (never colour only).

**Guardian (after booking):** top status hero: "Driver found" + big "4 min · 1.2 km" + provider avatar.
Sheet: "Cancel the others?" with the other legs as rows (avatar + fee pill "Free"/"May cost ≤ ₹50") and two big
buttons [Cancel others] [Keep all]. Cancel guide: numbered step cards (1 Tap Cancel ride, 2 Booked another ride,
3 Confirm) with the app's avatar and a "Show me" icon button; each leg row ticks green when proven. Reminders show
as a pulsing pill "2 still open". Loud alerts = red sheet with one clear action. Better-ride sheet: two driver
cards side by side (old vs new: minutes + km) and [Switch] [Keep].

**Bubble overlay:** 56 dp circular bubble with RideMate glyph and a count badge; tap opens a compact radial/row
menu of 4 icon actions (Read fares, Booked here, Show me, Done) with 1-word labels under each icon.

**Settings / My data / Developer:** grouped list rows with leading icons and one-word titles; long text only in
InfoSheets. Erase = red row → confirm sheet with the K1 sentence verbatim. Version row shows "Built with Muse Spark
Open" + certificate fingerprint (copy icon).

## 5. Copy rules (English + Hindi)
Buttons are verbs (≤ 3 words). Titles ≤ 5 words. No paragraph on a primary screen. Numbers beat words
("4 min · 1.2 km" not "Your driver is four minutes away"). Errors = what happened + one action
("Offline · Retry"). Legally required text (privacy notice, Erase wording, fair-play warning, 18+) is kept in full
but moved into InfoSheets/confirm sheets. Hindi strings follow the same brevity and use natural Hindi
(not transliteration).

## 6. Non-negotiables carried over
I5 (model text never shown — labels come from OCR/templates/strings), I2 (no unverified number), accessibility
(TalkBack labels, 48 dp, no colour-only state, font scaling to 200 % without clipping), English + Hindi parity,
"DEMO" labelling, the hard rule (RideMate never acts inside other apps).
