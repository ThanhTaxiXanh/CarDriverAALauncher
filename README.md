# CarDriverLauncher — Android + Android Auto prototype

**Stack (October 2026):** Kotlin (AGP 9.4.1 built-in), Gradle 9.6.0, API 37,
Car App Library 1.7.0, Mapbox Maps Android 11.33.0. No PC is needed to build via GitHub Actions.

## What is implemented
- One phone APK with configuration UI, ratios 30..80%, horizontal/vertical split,
  grid 1..3 rows × 1..3 columns, 4 widget types. Persisted via `SharedPreferences`.
- Full-screen **phone preview** with Mapbox 3D pitch, map view and configured widgets.
- Vietnamese solar/lunar conversion from the supplied `LunarCalendar.kt` (UTC+7),
  realtime GPS speed/distance/average, photo picker/slide on the phone,
  media session metadata and controls after user grants Notification Access.
- Manually georeferenced speed-limit/danger sign annotations. No remote traffic sign
  feed and **no automatic accurate road speed-limit database** are included.
- Android Auto CarAppService + NavigationTemplate + SurfaceCallback + experimental
  VirtualDisplay/Presentation view, loading saved configuration. Image slides are
  deliberately not displayed while driving; media controls are phone-only.
- `build_apk.yml` creates a release-signed APK from stored GitHub secrets;
  `create_keystore.yml` allows one-time key creation using GitHub only.

## IMPORTANT Android Auto restriction
This is a **developer prototype, not a public Play Store-ready navigation app**.
Android Auto does not guarantee arbitrary user-defined split/grid ratios, nor does
it officially permit a dashboard of unrelated interactive widgets in the map Surface.
Mapbox inside a VirtualDisplay may fail on particular hardware/hosts and needs
real-device testing. A Navigation-category app must provide a *genuine* route
planning and turn-by-turn experience and pass driver-distraction and Android Auto
quality review to be distributed. This project does not yet implement that.

For production, redesign Auto's UI using approved host-managed templates and build
turn-by-turn navigation. Use the phone preview for unrestricted layout editing.

## GitHub phone-only setup
1. Make a **private** GitHub repository and upload the contents of this project ZIP
   (at repository root, NOT nested inside another folder).
2. Repository → Settings → Secrets and variables → Actions → New repository secret:
   - `KEYSTORE_PASSWORD`: strong permanent password (>= 12 chars; no line breaks)
   - `KEY_ALIAS`: e.g. `cardriverrelease` (do not change for later builds)
   - `KEY_PASSWORD`: same as `KEYSTORE_PASSWORD` for PKCS12 compatibility
   - `MAPBOX_PUBLIC_TOKEN`: Mapbox public `pk.*` token; do not use private `sk.*`
3. Actions → **ONE TIME - Generate permanent keystore** → Run workflow ONCE.
4. Download the private artifact ZIP immediately; BACK UP `release.jks` offline.
   Copy ALL characters in `RELEASE_KEYSTORE_BASE64.txt` into the GitHub secret
   `RELEASE_KEYSTORE_BASE64`. DO NOT publish this text or commit it to source.
5. Delete the one-time workflow artifact from GitHub Actions immediately after
   you have backed up the key and stored the base64 secret.
6. Actions → **Build signed CarDriverLauncher APK** → Run workflow.
7. Download `CarDriverLauncher-release-<run number>` artifact ZIP and extract APK.
   Install it on your Android phone; updates use the same application ID/certificate.

**Never rerun keystore generation to replace an existing key**: updates signed by a
new keystore will not install over the prior APK. Keep a secure offline copy. If
secret values are lost but the keystore JKS backup is kept, set the same values again.
For updates, `versionCode` is based on the GitHub build workflow's `run_number`.
Keep that workflow's history/repository; for a new repository, raise
`BUILD_VERSION_CODE` above previous releases. The package ID must remain unchanged.

## Build compatibility
`./gradlew` in this ZIP is a **Gradle bootstrap script**, not a fully self-contained
standard Gradle Wrapper JAR (binary downloads were unavailable at packaging time).
GitHub's `gradle/actions/setup-gradle@v4` installs pinned Gradle 9.6.0 before the
script runs, so `./gradlew :app:assembleRelease` uses the exact version specified.
For a standard local wrapper, run `gradle wrapper --gradle-version 9.6.0` and commit
the generated `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, and `gradlew.bat`.

## Configuration and API keys
- Public Mapbox key is injected at build time into `BuildConfig`; this is normal for
  `pk.*` mobile SDK keys. Restrict it according to Mapbox account recommendations.
- Speed limits/road signs in maps are NOT inherently provided by Maps SDK.
  For accurate data, use legally licensed traffic datasets with timestamp and
  georeferencing, and warn users not to rely on data as a replacement for road signs.
- GPS telemetry is based on phone LocationManager, not vehicle CAN/OBD odometer.
- Phone must grant location runtime permission. Media widget requires user to enable
  Notification Listener Access in Android Settings.
- Do not use photo slides or custom interactive overlays on car while driving.

## Main source files
- `app/src/main/java/com/thanhtaxixanh/cardriverlauncher/MainActivity.kt` phone UI
- `SettingsStore.kt` layout JSON storage; GPS sign storage
- `DashboardView.kt` Mapbox 3D + widgets preview/experimental surface renderer
- `CarDriverService.kt` CarAppService + NavigationTemplate + SurfaceCallback
- `Telemetry.kt`, `MediaAccessService.kt`, `LunarCalendar.kt`

## Verification
Build with GitHub Actions. A clean Gradle dependency resolution and Android device
instrumentation run **have not been executed in the packaging environment**.
First validate CI compilation, then Android Auto DHU/real car, Mapbox rendering
inside the host surface, portrait/landscape, and driving-distraction requirements.
