# Quietube

A private, ad-free Android YouTube client written in Kotlin and Jetpack Compose.
It plays videos without Google Play Services, sends no analytics, and keeps your
YouTube session encrypted on the device. On the home screen the app is named
**YouPoop**.

> Quietube is an unofficial personal project. It is not affiliated with or
> endorsed by YouTube or Google. It relies on undocumented YouTube interfaces
> (via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) and
> InnerTube), which can change or break at any time.

## Features

- **Search and playback without an account**: search with suggestions,
  infinite paging and related videos. Playback uses Media3 with a background
  `MediaSessionService`, so notification, lock-screen and Bluetooth controls
  work. Quality is chosen by connection (1080p on Wi-Fi, 720p otherwise) and
  can be overridden. Live streams play over HLS.
- **Picture-in-picture, mini player and fullscreen**, with local resume
  positions stored in Room.
- **Optional YouTube account**: sign in through an isolated WebView, an
  experimental TV device-code flow, or a Netscape `cookies.txt` import. You
  then get Home, Subscriptions, History, Watch Later, Liked videos and
  Playlists, plus like, dislike and subscribe actions. Watch-history reporting
  is optional; successful history updates are silent.
- **Comments and descriptions**, with optional dislike estimates from
  [Return YouTube Dislike](https://returnyoutubedislike.com/) (off by default).
- **Play on TV**: launches the video in a TV's YouTube app through DIAL/Lounge,
  or, as an experimental option, streams directly from the phone to a DLNA
  renderer with no ads.
- **Opens YouTube links**: shared and opened links for youtube.com and youtu.be,
  including timestamps.
- **Material 3 Expressive interface**: System/Light/Dark themes, optional
  wallpaper colours, expressive controls and loading indicators, predictive
  back, a bottom bar on phones and a navigation rail on wider screens. Ratings
  and subscriptions update immediately, roll back on failure with Retry, and
  offer Undo after unsubscribe. Signed-out rating buttons explain sign-in only
  when tapped; the profile button opens Account directly.

## Privacy and security

- No Google Play Services, Firebase, analytics, crash reporting or HTTP logging.
- Session cookies are encrypted with an Android Keystore AES-256-GCM key and
  excluded from backups and device transfer. Cleartext traffic is disabled.
- The authenticated HTTP client sends credentials only to `www.youtube.com`
  and does not follow redirects. Cookies and authorization headers are never
  logged.
- Signed stream URLs stay in memory only.
- Signing out deletes the session, its key, caches, local resume data and
  search history.

The [development log](docs/development-log.md) has the full details.

## Building

Requirements: JDK 17, Android SDK Platform 37 and Build Tools 36. The Gradle
wrapper is included. Set `sdk.dir` in `local.properties`, or set
`ANDROID_HOME`.

```sh
./gradlew assembleDebug                      # debug APK
./gradlew testDebugUnitTest lintDebug        # unit tests and lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For daily use, build the release variant. It is shrunk with R8 (about 4 MB) and
scrolls much more smoothly than the debug build:

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, so it installs over a
debug build and keeps the signed-in session. Do not distribute it as is.

Thumbnail images are warmed two cards ahead and one behind on unmetered Wi-Fi,
through the existing image cache. This look-ahead stops on other networks and
cancels obsolete requests when scrolling or leaving the list. Video buffering
uses a white circular spinner; other loading indicators remain Expressive.

The foreground app requests 120 Hz on supported displays, using an available
fallback on other displays. To prevent low-brightness flicker when the panel
switches to its idle rate, the app keeps redrawing while in the foreground—even
on static pages. This uses more foreground idle GPU/battery power. Redraws and
the refresh request stop in PiP and when paused. Global adaptive-refresh settings
are unchanged; Android can still apply display, power-saving and thermal limits.

### UI samples

The isolated `samples` variant renders the production controls with synthetic
state, without reading an account or making requests from its sample screen:

```sh
./gradlew assembleSamples
adb install -r app/build/outputs/apk/samples/app-samples.apk
adb shell am force-stop dev.quietube.samples
adb shell am start -n dev.quietube.samples/dev.quietube.ui.ExpressiveSamplesActivity --ez dark true
```

It installs beside the normal app. Use `--ez dark false` for light samples.
This variant and its sample activity are excluded from debug/release builds.
For an offscreen image-cache check, add `--ez thumbnails true`; this mode requests
one public YouTube thumbnail and shows whether it was cached before display.

### Tests

- `app/src/test`: offline JVM tests using fakes, MockWebServer and sanitized or
  synthetic fixtures. No test contacts YouTube.
- `app/src/androidTest`: a few device tests for encrypted session storage and
  the chunked data source. Run them on a spare device, because the test runner
  may uninstall the app and its data.

## Project layout

```
app/src/main/java/dev/quietube/
  account/    sign-in, encrypted session, InnerTube client and feed parsers
    actions/  likes, subscriptions, history reporting, sync preferences
    oauth/    TV device-code flow (experimental)
  cast/       Play on TV: DIAL/Lounge and direct DLNA streaming
  data/       VideoRepository, NewPipeExtractor source, comments, prefetching
  di/         Hilt modules
  links/      YouTube link parsing
  playback/   Media3 service, player state, resume database
  ui/         Compose screens, theme, navigation
docs/
  development-log.md   phase-by-phase notes and verification records
  ui-refresh/          brief and phase reports for the Material 3 redesign
art/logo.svg           source artwork for the launcher icon
```

## Contributing

Coding agents and human contributors should read [AGENTS.md](AGENTS.md) first.
It covers the workflow, code style and the privacy rules every change must keep.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). NewPipeExtractor is also
GPL-3.0-or-later; keep upstream notices when redistributing.
