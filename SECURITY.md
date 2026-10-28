# Security policy

RideMate is built and run by one person, Baharul Islam (Barpeta, Assam). Security reports are very welcome and are
read by him directly.

## How to report a problem

- **Email:** ibaharul567@gmail.com, with a subject line that starts with `[RideMate security]`.
- **Please do not** open a public GitHub issue, pull request or discussion for a security problem.
- Include: what you found, the steps to reproduce it, the app version (Settings → About) or the commit/tag, and what
  an attacker could do with it. Screenshots or a short screen recording help. **Blur any phone numbers, OTPs, number
  plates, names or tokens** before you send anything.
- If the problem is about personal data (for example, you think RideMate kept data it should not), the same address
  is also the privacy grievance contact. See [docs/privacy-notice.md](docs/privacy-notice.md).

## How fast you will hear back

| Step | Target |
|---|---|
| First reply (we got it) | within 72 hours |
| Assessment (is it real, how serious) | within 7 days |
| Fix or safe workaround for a critical issue (for example, a leaked key, data exposure, or a way to spend the AI credit without limits) | within 7 days, often the same day through a remote kill switch |
| Fix for other confirmed issues | within 30 days |

If a fix needs more time, you will be told why and when. With your permission, you will be credited in the release
notes.

## In scope

- The Android app `in.ridemate.app` (latest release on GitHub Releases, and the `main` branch).
- The backend `ridemate-api` (the FastAPI web app on Modal) and its API under `/v1/*` and `/ondc/*`.
- Firestore security rules (`firebase/firestore.rules`) and how the app and server use Firebase.
- How the app handles Remote Config, notification text and screen text (for example, prompt injection that makes
  RideMate show a wrong price, start a cancel by itself, or reach the AI without limits).
- The spend limits: any way to use the Modal credit beyond the documented fair-use limits.
- Release signing: any APK that claims to be RideMate but whose certificate SHA-256 is not
  `94:F2:DD:5F:DB:02:AD:ED:46:31:CB:0A:09:4A:0B:EE:81:05:32:C1:C8:AE:22:23:C1:7D:1C:7A:A4:22:7A:A0`.

## Out of scope

- Uber, Ola and Rapido themselves. Please report problems in those apps to their owners.
- Modal, Firebase, Google and GitHub platform issues. Please report those to the vendor.
- The **ONDC demo seller** used during the hackathon (it is a clearly labelled mock that handles no real rides).
- Volumetric denial of service, spam, social engineering, and physical attacks.
- Findings that need a rooted phone, a phone with USB debugging already authorised to the attacker, or a modified
  RideMate APK.

## Rules for testing

- Use only your own accounts and your own phone.
- **Never automate or script Uber, Ola or Rapido** while testing RideMate. RideMate itself never acts inside those apps
  and testing must not either.
- Do not access, change or delete other people's data. If you see someone else's data by accident, stop, do not keep
  it, and tell us.
- Do not run tests that spend large amounts of the AI credit. A few requests are enough to show a limit bypass.

We will not take legal action or file a complaint against anyone doing good-faith research that follows these rules,
and we will thank you. (If your report shows that a real incident happened, RideMate may still have to notify CERT-In
and the Data Protection Board of India, as Indian law requires; that notice is about the incident, not about you.)

## Supported versions

Only the latest release on GitHub Releases receives fixes. Old versions are asked to update in the app.
