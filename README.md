# CarDriverLauncher — Android + Android Auto prototype

**Stack (October 2026):** Kotlin (AGP 9.4.1 built-in), Gradle 9.6.0, API 37,
Car App Library 1.7.0, Mapbox Maps Android Auto Extension 11.33.0. No PC is needed to build via GitHub Actions.

## What is implemented
- One phone APK with configuration UI, ratios 30..80%, horizontal/vertical split,
  grid 1..3 rows × 1..3 columns, 4 widget types. Persisted via `SharedPreferences`.
- Full-screen **phone preview** with Mapbox 3D pitch, map view and configured widgets.
- Vietnamese solar/lunar conversion from the supplied `LunarCalendar.kt` (UTC+7),
  realtime GPS speed/distance/average, photo picker/slide on the phone,
  media session metadata and controls after user grants Notification Access.
- Offline national OSM traffic extraction pipeline (GitHub Action from Geofabrik Vietnam PBF), regional JSON/ZIP import, directional way matching, speed-limit HUD, and traffic sign/camera annotations. Missing values are shown as UNKNOWN; the source is not a certified legal dataset.
- Android Auto CarAppService + NavigationTemplate + official Mapbox MapSurface rendering,
  plus opt-in native BitmapWidget panels (NO VirtualDisplay/Presentation). Photos, QR and
  interactive media controls are hidden on the car.
- `build_apk.yml` creates a release-signed APK from stored GitHub secrets;
  `create_keystore.yml` allows one-time key creation using GitHub only.

## IMPORTANT Android Auto restriction
This is a **developer prototype, not a public Play Store-ready navigation app**.
Android Auto does not guarantee arbitrary user-defined split/grid ratios, nor does
it officially permit a dashboard of unrelated interactive widgets in the map Surface.
The custom Mapbox bitmap panels may differ by host/screen size and need
real-device testing. A Navigation-category app must provide a *genuine* route
planning and turn-by-turn experience and pass driver-distraction and Android Auto
quality review to be distributed. This project does not yet implement that.

For production, redesign Auto's UI using approved host-managed templates and build
turn-by-turn navigation. Use the phone preview for unrestricted layout editing.

## GitHub phone-only setup
1. Keep the main source repository PUBLIC for GitHub Actions builds. Generate and
   securely back up the permanent Keystore using a separate PRIVATE repository.
2. In the PUBLIC source repository → Settings → Secrets and variables → Actions → New repository secret:
   - `KEYSTORE_PASSWORD`: strong permanent password (>= 12 chars; no line breaks)
   - `KEY_ALIAS`: e.g. `cardriverrelease` (do not change for later builds)
   - `KEY_PASSWORD`: same as `KEYSTORE_PASSWORD` for PKCS12 compatibility
   - `MAPBOX_PUBLIC_TOKEN`: Mapbox public `pk.*` token; do not use private `sk.*`
3. In the PRIVATE keystore repository, run **ONE TIME - Generate permanent keystore** ONCE.
4. Download the private artifact ZIP immediately; BACK UP `release.jks` offline.
   Copy ALL characters in `RELEASE_KEYSTORE_BASE64.txt` into the GitHub secret
   `RELEASE_KEYSTORE_BASE64`. DO NOT publish this text or commit it to source.
5. Delete the one-time workflow artifact from GitHub Actions immediately after
   you have backed up the key and stored the base64 secret.
6. In the PUBLIC source repository, run **Build signed CarDriverLauncher APK**.
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
- Speed limits/road signs do NOT come from Mapbox Maps SDK or BRF routing defaults.
  Only explicit OSM maxspeed/point tags are imported, and they may be incomplete.
  See docs/TRAFFIC_VIETNAM.md for extraction, provenance, and unverified report rules.
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
The latest GitHub Actions build has assembled the release APK, verified its
signature, and uploaded an artifact. Android Auto on a real head unit has NOT
been verified for this update. Test the car surfaces, layout and driver safety
requirements while parked before using the application.

## V16: Android Auto map and custom 3D navigation arrow

**Default (more compatible)**: the official Mapbox Android Auto Extension
`com.mapbox.extension:maps-androidauto:11.33.0` renders Standard 3D on the
car's MapSurface. A custom ground-level arrow model is bundled as
`app/src/main/assets/driver_arrow.gltf`. The driving camera follows phone
GPS and bearing. The street name below the marker depends on Android Geocoder
and can be absent without a network geocoder.

**Optional layout beta**: on the phone, turn on
"Hiển thị bố cục Mapbox + Widget trên Android Auto (Beta)" and reopen the
Android Auto app. Saved horizontal/vertical map split, rows/columns and cell
height/width ratios are rendered through a lifecycle-aware Presentation.
Mapbox Surface rendering is used as a fallback when this approach fails.
On-car photos and interactive music controls are intentionally omitted.

**Lane safety**: The car icon uses phone GPS and is **not lane-accurate**.
Standard 3D maps and ModelLayer DO NOT unlock Mapbox 3D Lanes, an additional
automotive lane-level navigation product. Coverage announced by Mapbox is
primarily in selected US, European and Japanese cities; Vietnam detailed
lane coverage is not guaranteed. Never use this prototype for lane guidance.

See:
- https://github.com/mapbox/mapbox-maps-android/tree/main/extension-androidauto
- https://docs.mapbox.com/android/maps/examples/android-view/3D-model-layer/
- https://www.mapbox.com/3d-lanes

Build success is not proof of compatibility with a particular Android Auto
host. Test the prototype while parked; a compliant navigation app requires
actual route guidance and approval before public driving distribution.

## V21+: Vietnam traffic extraction & community contribution

Download OSM speed limits / signs / cameras for **Vietnam only** using
[Build Vietnam Traffic OSM Data](.github/workflows/build_vietnam_traffic.yml).
After the workflow finishes, download its artifact ZIP and select it directly in
the phone application's **Nhập dữ liệu Việt Nam (ZIP hoặc JSON)** picker;
the app imports packages into local SQLite and keeps existing driver reports.

The phone's **Báo cáo tốc độ / biển báo / camera / dịch vụ / làn đường** form
records exact GPS, intended vehicle travel direction, lane and sign side. Data
stays offline until the user explicitly exports it. Community reports have a
visible question mark and never override an OSM road speed. A missing or
ambiguous value is displayed as UNKNOWN rather than guessed from BRF.
There is **no automatic public submission/moderation backend yet**.

See [docs/TRAFFIC_VIETNAM.md](docs/TRAFFIC_VIETNAM.md) for the complete data
contract, Geofabrik/OSM ODbL attribution, lane/direction limitations and
one-click ZIP import instructions. Do not treat this prototype as a substitute
for posted road signs or a certified lane-level speed-limit product.

## V48: persistence, car map/HUD and static photo

- Fixed persisted 2-row/1-column ratios on app reopening: Android Spinner's
  initial `onItemSelected` callbacks no longer reset `rowPercents` or
  `columnPercents` when row/column counts are unchanged. Slider progress saves
  immediately, `onPause` commits current configuration.
- Corrected Mapbox `EdgeInsets(top,left,bottom,right)`: when the widget panel
  occupies the right 45% the **right padding**, not left padding, receives 45%.
  The GPS chevron is therefore framed inside the actual left map portion.
- Car Surface shows independent circular **GPS speed** and **OSM speed limit**
  badges. Missing/ambiguous legal limits use `—`; values come from OSM tags and
  must not be relied upon over posted signs.
- OSM-point speed signs now contain their actual numeric value (white center,
  red circle) instead of the generic exclamation icon; camera and other map
  icons remain georeferenced to OSM/contributor point data.
- PHOTO cell renders the **first user-selected persisted image** from Android's
  Storage Access Framework as a **static image** on the Mapbox Surface, using
  downsampled background decoding. No car slideshow transitions or media buttons.
- Car `NavigationTemplate` **map ActionStrip** provides a real, clickable
  report icon. One tap saves a `USER_PENDING` GPS bookmark; on the phone,
  **Hoàn tất điểm báo cáo đánh dấu trên Android Auto** allows the user to
  categorize sign/camera/limit and specify travel direction/lane while parked.
  The drawn map overlay is not an interactive button.
- This remains a developer Android Auto prototype. A photo widget or unrelated
  dashboard on a car navigation surface may be incompatible with Android Auto
  distribution policies and individual hosts. Do not handle reporting forms
  while driving; community traffic reports are unverified.
