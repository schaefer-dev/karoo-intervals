# CLAUDE.md

Karoo extension: runs intervals.icu planned workouts with its **own workout engine** and shows
them on a **full-page custom screen** (one graphic data field, meant to sit alone on a profile
page at 60×60). No ERG/trainer control (the Karoo SDK has none) — targets are display + beeps +
in-ride alerts; each step change marks a lap.

## Architecture

- `workout/` — pure model. `WorkoutParser` turns intervals.icu `workout_doc` trees into flat
  `Step`s (repeats expanded; `%ftp` / `w` / `power_zone` / `%lthr` targets; `ramp: true` → ramp,
  otherwise `start/end` is a range with midpoint target). `Profile` = bar geometry shared by the
  ride-screen bitmap and the Compose picker. `ZoneColors`.
- `intervals/IntervalsApi` — requests + `sync()` with injected HTTP (Basic auth `API_KEY:<key>`).
  Endpoints: `/athlete/0/sport-settings/Ride` (FTP, zones, LTHR) and
  `/athlete/0/events?category=WORKOUT` (yesterday … +7 days).
- `data/WorkoutRepository` — DataStore cache of the raw sync JSON (parsed on read), selected
  workout, user-entered API key (falls back to `BuildConfig.INTERVALS_API_KEY`), debug simulator flag.
- `engine/WorkoutSession` — pure state machine on ELAPSED_TIME **seconds**; `WorkoutCoordinator`
  merges all inputs into one sequential flow (JVM-testable). `WorkoutRunner` (process singleton)
  runs it, publishes `state`, and maps events to effects (`MarkLap`, `PlayBeepPattern`, `InRideAlert`).
- `screen/WorkoutScreenRenderer` draws everything into a Canvas bitmap;
  `WorkoutScreenDataType` is the thin field adapter. Tap zones are transparent views in
  `res/layout/field_workout.xml` whose weights **must match** `WorkoutScreenRenderer.Layout`.
  `TapReceiver` handles skip / pause; the gear opens `ui/WorkoutsActivity`.
- `ui/WorkoutsActivity` — Compose picker (Back to ride, Sync, current workout, this week, account).
  Has its own `KarooSystemService` so Sync uses the Karoo's network.

**ELAPSED_TIME is milliseconds** in the SDK; `elapsedSeconds()` is applied once in `WorkoutRunner`.

## Build / test

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`.env` (git-ignored) holds `INTERVALS_ICU_API_KEY=...`; only debug and `personal` builds embed it.
`assembleRelease` stays keyless (shareable; users enter their key in the app, 👤).
`assemblePersonal` = release settings + key, for the owner's own Karoo only. Test fixtures in `app/src/test/resources/` are trimmed real API
responses — never commit personal fields (email, api key) into them.

Debug builds have a simulator toggle (👤 section): synthetic streams at 5× speed, power follows target.

## Conventions

Same as `../decoupling_karoo`: logic in pure Session/Coordinator/Parser with tests, Android classes
stay thin; no mocking frameworks; SDK source at `../karoo-ext-sdk/lib/`.
