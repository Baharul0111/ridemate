# Demo video script (3 minutes)

A shot list with timestamps and voice-over for the hackathon video. Total **3:00**; keep it under 3:00.

## Before recording

- **Phone:** Baharul's own Android phone with the release build. Turn on Do Not Disturb (except ride apps), set the
  font size to normal, battery above 50%, clean home screen.
- **Screen recording:** Android's built-in screen recorder (no audio from the phone); voice-over recorded separately
  and laid over in editing. Add captions for every line of voice-over.
- **Privacy:** RideMate already masks phone numbers, OTPs, plates and names before upload, but the *ride apps* show them
  on screen. **Blur any driver name, phone number, plate, OTP, home address or your own name** in the edit before
  publishing. Use a public place (for example a railway station or a landmark) as the destination.
- **Real ride apps:** Baharul records the live segment himself, with his own accounts, by hand. Nothing is automated or
  staged with fake ride-app screens. Cancel while searching where possible (usually free).
- **Demo segment:** uses only the fictional providers Kestrel Go, Pepper Rides and Tamarind Cabs, labelled DEMO.
- **Honesty:** if a "Couldn't read · check in app" row appears, keep it in the shot. Don't re-take to hide it.
- Music: none, or royalty-free at low volume.

## Shot list

| Time | Shot (what's on screen) | Voice-over |
|---|---|---|
| **0:00–0:15** | Quick cuts (recorded by hand on Baharul's phone): three ride apps open one after another, the same destination typed three times. Then a plain text card, clearly a graphic and not a ride-app screen: "Forgot to cancel? Cancellation fee." Title card: "RideMate". | "Booking a ride in India means opening Rapido, Uber and Ola one by one, comparing prices in your head, booking two just in case, and then forgetting to cancel one. That forgotten booking costs you a fee and wastes a driver's trip." |
| **0:15–0:25** | RideMate home screen. Text overlay: "RideMate never taps inside the ride apps. You tap Book. You tap Cancel." | "RideMate fixes the juggling, with one hard rule: it never taps or types inside the ride apps. You stay in control of every booking." |
| **0:25–0:40** | Open **Try the demo** (DEMO banner visible). The demo trip and vehicle types (bike, auto, cab) on screen; the three fictional apps listed. | "Anyone can try it, even without Indian ride apps. Try the demo uses three made-up ride companies and runs the real pipeline." |
| **0:40–1:05** | On a Kestrel Go fare screen, tap the floating bubble → rows appear. Repeat for Pepper Rides and Tamarind Cabs. Then the comparison list grouped by vehicle, cheapest marked; zoom on "seen at hh:mm:ss". If a "Couldn't read · check in app" row appears, hold on it for a second. Overlay: "Muse Spark Open + ML Kit must agree". | "One tap reads the screen. Two independent readers check every price: Meta's open-weights Muse Spark Open, running on Modal, and Google's on-device text recognition. A price appears only if both agree. If they don't, RideMate says 'Couldn't read' instead of guessing. Only verified prices can be marked cheapest." |
| **1:05–1:20** | Demo: a driver is found in one app. Sheet: driver 1.2 km away, 4 min, fee note for the others; buttons "Cancel the others?" Yes / No. | "You book where you like. The moment a driver accepts, RideMate tells you how far away they are, and asks: cancel the others?" |
| **1:20–1:50** | Tap Yes → the next demo app opens → instruction card "Tap Cancel → Booked another ride → Confirm" → "Show me" arrow beside the Cancel button (only if the build supports Show me; otherwise skip it) → tap Cancel → status "Cancelled · proven by the app's notification". Then cut to a reminder notification for the remaining booking ("Still active: Tamarind Cabs") and its proof arriving. | "It opens each other app, shows exactly where to tap, and you tap Cancel yourself. RideMate only counts a cancel when it sees proof from the app, and until every extra booking is proven gone, it keeps reminding you, even if you close RideMate." |
| **1:50–2:05** | Demo: "Keep searching" on; a better-ride sheet compares two drivers (ETA, distance, fee). Tap Yes → guided cancel of the old one. | "If you chose to keep searching and a much closer driver turns up, it shows you both and changes nothing unless you say yes." |
| **2:05–2:12** | Demo ride ends → "Pay in Kestrel Go" → the demo app opens. | "At the end, it opens the ride app so you pay there. RideMate never touches your money." |
| **2:12–2:35** | **Live, on Baharul's own phone** (recorded by him): RideMate opens Uber with pickup and drop already filled; Rapido opens with "Paste drop"; tap the bubble on a real fare screen → Android's capture prompt → verified rows in RideMate (personal details blurred in the edit). A real "searching" booking is cancelled by hand while RideMate shows the card and then "Cancelled · proven". | "And this is the real thing, on my phone in Assam, with my own accounts. Uber opens with the route ready, prices land in one list, and when I cancel, RideMate waits for proof." |
| **2:35–2:45** | ONDC tab labelled "ONDC demo seller": search → quotes → book → cancel shows the fee first → confirm. | "On India's open ride network, ONDC, RideMate can search, book and cancel by itself after your tap. For the hackathon this runs against a clearly labelled demo seller." |
| **2:45–2:53** | Developer budget panel (AI spent of $80) → manual mode (for this shot, turn AI off with the `ai_reader_enabled` kill switch, and back on afterwards): the manual-mode message, typing a price, the guardian still active. | "It runs only on the hackathon's Modal credit, with a spending meter and daily pacing. When the AI budget pauses, you type the price, and the guardian keeps protecting you." |
| **2:53–3:00** | End card: "RideMate · Built with Muse Spark Open · github.com/Baharul0111/ridemate". | "RideMate. Built with Muse Spark Open, for my world." |

## Checklist after editing

- [ ] Length ≤ 3:00.
- [ ] "Built with Muse Spark Open" visible on screen and said aloud.
- [ ] DEMO label visible in every demo shot; the live segment is clearly the real apps.
- [ ] No unblurred driver names, phone numbers, plates, OTPs or addresses.
- [ ] Captions on; audio levels even.
- [ ] Uploaded where the submission form asks; link added to the README and the submission.
