# Fonts used by the generators

| File | Family | Licence | Source |
|---|---|---|---|
| `NotoSans-Regular.ttf`, `NotoSans-Bold.ttf` | Noto Sans 2.005 (Latin, ₹) | SIL OFL 1.1 (`OFL-NotoSans.txt`) | TeX Live `fonts/truetype/google/noto` (the Noto project's release) |
| `Mukta-Regular.ttf`, `Mukta-Bold.ttf` | Mukta 2.538 (Devanagari, ₹) | SIL OFL 1.1 (`OFL-Mukta.txt`; licence in the font's name table) | TeX Live `fonts/truetype/public/ektype-tanka` (Ek Type) |

These are copies of OFL files that were already installed on the build machine, so nothing was downloaded.
The task suggested Noto Sans Devanagari, but Mukta is used instead. It is OFL as well, and it covers Devanagari
and ₹.

Changing any font file changes every demo PNG's SHA-256. If that happens, regenerate and commit the demo
assets, labels and synthetic fixtures together, and update the backend's demo allowlist.

The generators lay text out with Pillow's BASIC engine, so the bytes do not depend on libraqm. Rendered Hindi
is therefore limited to words that need no complex shaping (`ridemate_eval/fonts.py`).
