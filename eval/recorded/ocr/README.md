# Recorded ML Kit OCR

`<sha256>.json` files exported from the owner's phone (engine `mlkit-text-v2`; see `eval/recorded/README.md`).

The 16 files here are the bundled demo screens, recorded on a moto g57 power (Android 17, API 37) on 29 Oct 2026 by
the instrumented `DemoOcrRecorderTest` (`android/app/src/androidTest/.../e2e/`): ML Kit Text Recognition v2, Latin +
Devanagari, merged exactly as Channel B sees a demo screen. Two recordings gave identical tokens. The Kotlin
`AccuracyTest` pairs them with `eval/recorded/channel_a` as its `recorded` suite.
