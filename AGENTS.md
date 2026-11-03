# AGENTS.md

Unofficial PromoDJ Trendy client: a single-module Android app in plain Java, with no
AndroidX, no third-party dependencies, and Russian UI text.

## Build and verify

- Primary signed build: `./build.sh` → `build/Trendy-<version>.apk`. It runs aapt2 →
  javac → d8 → zipalign → apksigner without Gradle.
- `build.sh` needs `aapt2 javac jar d8 zipalign apksigner zip keytool` and `android.jar`
  via `ANDROID_JAR`, `.sdk/android.jar`, or a standard Android SDK path. These tools are not
  in the repo; install the Termux packages listed in `README.md` or use Android Studio.
- The Gradle wrapper is committed; `./gradlew :app:assembleDebug` builds the debug APK
  (Gradle 9.6.0, AGP 9.5.0). The manual script and Gradle build are separate paths; keep both working.
- No tests, lint, formatter, or codegen. Verification = successful APK build,
  `apksigner verify`, and a manual device smoke test (feed load, playback, notification).
  CI is `.github/workflows/build.yml`: on push/PR it builds both Gradle debug and
  `./build.sh`, and on `v*` tags it fails if the tag does not match `versionName`.
- Version source of truth is `version.properties`, read by both `build.sh` and Gradle.
  Bump it with `./tools/bump-version.sh patch|minor|major` (always increments `versionCode`);
  do not hardcode versions in `build.sh` or `app/build.gradle.kts`.
- `dist/Trendy-1.0.apk` is the old released artifact. `dist/SHA256SUMS` has a stale nested
  path, so `sha256sum -c` fails; compare the hash manually and update both on release.
- `keystore.jks` is intentionally in the project and is the update-signing key. It is
  gitignored: never commit, publish, or share it; do not regenerate it for updates.
- Remote is `https://github.com/kostya4000/Trendy` (private, `main`). Release flow:
  `./tools/bump-version.sh`, `./build.sh`, commit, `git tag -a v<version>`, push tags,
  then publish a GitHub Release with `build/Trendy-<version>.apk` and `SHA256SUMS.txt`.
  Never attach `keystore.jks` or `local.properties` to a release.

## Build quirks

- `app/src/main/AndroidManifest.xml` deliberately has **no** `package` attribute. For the
  manual build, `build.sh` injects `package="com.promodj.trendy"` with `sed` into a temp
  copy. Keep the `<manifest ...>` opening intact; Gradle gets the package from `namespace`.
- `build.sh` compiles with `-source 8 -target 8`; do not use Java 9+ language or stdlib
  features (`var`, newer collection/stream APIs, etc.). Gradle uses Java 17, but the manual
  build must remain Java 8 compatible.
- Do not add AndroidX/Material or external dependencies (`android.useAndroidX=false` in
  `gradle.properties`). Use framework classes: `android.app.Activity`,
  `Theme.Material.NoActionBar`, `org.json`, `HttpURLConnection`, `MediaPlayer`.
- `minSdk 23`, `targetSdk 34`; use the existing `Build.VERSION.SDK_INT` guards rather than
  raising minimums.

## Architecture and data flow

- `MainActivity` loads the feed and owns genre/filter chips plus the mini-player.
  `PlayerActivity` is the full-screen player. Both bind `PlayerService`, which is the sole
  playback owner (MediaPlayer, `android.media.session.MediaSession`, foreground notification).
- Activities control playback through `PlayerService.ACTION_*` intents/public helpers, not
  by touching `MediaPlayer` directly.
- `AudioCache` downloads the current track to `cacheDir/audio/<id>.mp3`; `PlayerService`
  prefers that file and falls back to the network (deleting the bad cache entry) on failure.
  The player's cache button drives it; `TrackAdapter` marks cached rows.
- TV/head-unit support is framework-only: `LEANBACK_LAUNCHER` + `tv_banner`, optional
  touchscreen/leanback/automotive features, no portrait lock, and `layout-land/` player.
  Keep interactive views `focusable` with the `bg_focus_*` foregrounds and the ListView
  `bg_row_focus` selector so D-pad/remote focus stays visible. Don't re-add
  `android:screenOrientation="portrait"`.
- `layout-land/activity_main.xml` is a two-pane screen: `@id/playerPanel` on the left with the
  mini-player ids (`miniCover`, `miniTitle`, `miniArtist`, `miniProgress` as SeekBar,
  `miniPrev`, `miniPlay`, `miniNext`) plus `panelCache`/`panelDownload`, list and filters on
  the right. `MainActivity` detects it via `findViewById(R.id.playerPanel) != null`
  (`panelMode`) and keeps the panel visible without a track. Portrait keeps
  `view_mini_player.xml`; never assume `miniPlayer` exists.
- D-pad reachability is explicit: genre chips set `nextFocusDown` to `@id/filterAll` (and
  `filterAll` up to the selected chip), filters set `nextFocusDown` to `@id/list`, the list
  sets `nextFocusLeft` to `@id/miniPlay` and `miniNext` sets `nextFocusRight` to `@id/list`.
  Do **not** put `nextFocusLeft` on every filter tab: it made LEFT jump straight to the
  player instead of moving between tabs. Genre chips and landscape filters use the
  `bg_genre_chip` / `bg_filter_tab` state-lists (selected/focused) plus
  `color/genre_chip_text` / `color/filter_tab_text`; `setForeground` rings do not work for
  programmatically created chips.
- Remote seeking is handled explicitly in `MainActivity` (`miniProgress` `OnKeyListener`,
  ±5 s per LEFT/RIGHT) because TV boxes may not move a `SeekBar` with the D-pad. UP/DOWN
  leave the seek bar for `miniInfo`/`miniPlay`.
- The panel cover is hidden when the panel is too short for it (phone landscape); on TV
  there is room.
- `PromoDjApi` scrapes `https://promodj.com/trendy/<genre>[/<filter>]` HTML with the
  `TRACK_LINK` regex, then enriches tracks in batches of 20 via `POST /api/multi.json`
  (`players/config`). There is no API key. If the feed breaks, inspect the current PromoDJ
  markup/response before editing parsing code — the regex is the fragile boundary.
- `Http` intentionally sends a browser User-Agent, Russian `Accept-Language`, and
  `Referer: https://promodj.com/trendy`; preserve these when changing networking, since the
  feed scraping and `players/config` calls rely on looking like a browser session.
- The playback queue is passed as `ArrayList<Track>` in an Intent extra, so `Track` must stay
  `Serializable`. Adding fields also requires updating both `save()` and `load()` in
  `TrackCache`.
- Feed cache: `cacheDir/trendy/<genre>_<filter>.json`; cover cache: `cacheDir/images`;
  audio cache: `cacheDir/audio/<trackId>.mp3`. Selected genre/filter is persisted in
  `SharedPreferences` named `trendy`.
- `MainActivity.GENRE_KEYS` must match PromoDJ's URL slugs; display names are separate.

## Conventions

- All UI copy is Russian. Put new text in `app/src/main/res/values/strings.xml` (some legacy
  strings are inline); do not introduce English UI text.
- Keep the existing file/class-per-concern layout and framework-only style; see `README.md`
  for the file map.
