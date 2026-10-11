# ZenFlow 🌿

A fully offline meditation app for Android: layered soundscapes with endless
crossfades, synthesized sleep/focus tones, breathing patterns, presets,
progress (streaks · weekly chart · milestones · history), daily contemplations,
and a private, on-device practice log. **Zero network access — enforced at the
manifest level.**

## Open & run

1. Open the project folder in **Android Studio** (it generates the Gradle
   wrapper and syncs automatically).
2. Run the `app` configuration on a device or emulator.

## Sounds

Bundled sounds are **optional** — tones work with zero files. To add nature
sounds, drop MP3s into `app/src/main/res/raw/`:

    rain_01.mp3 … rain_20.mp3   (any count; shuffled endlessly with crossfades)
    fire.mp3                    (a single file also loops seamlessly)

Users can also import their own audio at runtime via **Sound Library → ＋ Import**.

## Release

- Generate a keystore, then create `keystore.properties` (git-ignored):

      storeFile=../zenflow-release.keystore
      storePassword=...
      keyAlias=zenflow
      keyPassword=...

- `./gradlew bundleRelease` → AAB in `app/build/outputs/bundle/release/`.
- Always install a release build once before shipping: R8 + resource shrinking
  are enabled, and `res/raw/keep.xml` keeps the sound database intact.

## Tests

    ./gradlew testDebugUnitTest            # JVM + Robolectric
    ./gradlew connectedDebugAndroidTest    # device/emulator

CI runs both on every push (`.github/workflows/android.yml`).
