# V61 – Android Auto instant traffic reports, icons, delayed recenter

## Android Auto report menu (native host UI, no arbitrary Android Dialog)

- Click the **!** map action (host-owned map strip).
- Host opens `CarTrafficReportScreen`:
  - **Speed** → preset **30/40/50/60/70/.../120** or end-limit.
  - **Signs** → paginated group of user-supplied sign drawings.
  - **Camera** → speed, red-light, AI, enforcement, other.
  - **Services** and **lanes**.
- Selecting **50** records a `TrafficEvent` immediately at the **latest GPS fix when
  the 50 item is tapped**. The code is `maxspeed`, `limit=50`,
  `source=USER_PENDING`, `verified=false`, `lane=UNKNOWN`, and
  `drivingBearing` is taken from the moving vehicle if reliable. The report
  appears on Mapbox as a pinned sign and survives app restarts in SQLite.
- No phone completion is required for basic speed/sign/camera reports. Additional
  lane or roadside detail can be supplied later on the phone, if desired.
- If the host cannot show the GridTemplate/MapWithContentTemplate, the fallback
  continues saving an unclassified GPS bookmark for phone review.
- Real-time GPS fix must have accuracy <=75 meters and an age <=15 seconds.
- **Driving restrictions remain under Android Auto host control**. Menus might be
  blocked, simplified or disallowed when the vehicle is moving, especially by
  OEM safety rules. The app must not circumvent those protections.
- Native Android Auto content uses GridTemplate within MapWithContentTemplate for
  Car API >=7, or GridTemplate standalone on older host APIs. The host decides
  its actual visual appearance and number of supported tiles. Up to 6 items
  are sent per page. A long list is paginated within the SAME screen to respect
  the five-step task-flow constraint.

## Imported traffic art

The uploaded `assets.zip` contained resources of a Flutter project.
We extracted a **68-icon traffic-only subset**, downsized to a single
`app/src/main/assets/traffic_signs_atlas.webp`, ~41KB. Names are mapped in
`TrafficIconAtlas.NAMES` and `TrafficSignCatalog`. Categories:
29 restrictions/alerts, camera illustrations, OSM-related service illustrations,
lane diagrams and speed-limit HUD images. App logos, fonts and unrelated assets
are NOT copied to the repository.

Both Android Auto report grid, phone report grid, and Mapbox georeferenced
reported points use the same bundled artwork. Unmatched OSM sign codes fall
back to an ordinary sign category icon. User-provided pictures do not prove the
existence or locations of any physical sign. Review copyright/license rights
for reuse before publishing or distributing publicly.

## Return to vehicle location after gestures

Mobile (MapView) touch events and Android Auto host callbacks `onScroll`,
`onFling` and `onScale` notify `CameraFollowGate`. While touched or for
5/10/15/20/30/60 seconds from the **last** gesture, the GPS camera-follow
feature is paused. The blue driver marker, traffic events and GPS telemetry
continue updating. Camera follow resumes automatically when the idle timeout
elapses. The setting is persisted using `LayoutConfig.recenterDelaySec`
(default 10s) and `SettingsStore`. No separate background task needed.
This recenter timeout also works on a stationary phone if GPS delivers no new
fix; the UI periodic tick restores the follow camera after the timeout.

The host can handle pan/zoom differently by display; native camera movement
and underlying Mapbox callback support require physical-car verification.

## Restrictions & data semantics

Only a photographed or driver-selected traffic sign is a **USER_PENDING**
report; it is NOT a certified legal speed limit. Do not use driver reports to
overwrite road limits from verified OSM way data without moderation. `LANE`
is unknown for fast reports; phone GPS cannot determine the exact lane.
The road's actual posted sign takes precedence. Drivers should operate menus
only when it is safe and legally permitted to do so.
