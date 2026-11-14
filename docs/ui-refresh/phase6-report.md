# UI refresh — Phase 6

## Implementation

Released as 0.6.0 (versionCode 22). Source paths below are relative to `app/src/main/java/dev/quietube/`.

1. **One player layer:** the `player` route and `ui/MiniPlayer.kt` are gone. `MainActivity.kt:180` wraps the app in a SharedTransitionLayout and draws `PlayerLayer` (`ui/PlayerScreen.kt:97`) over it when a video is playing or opening. A spacer after the NavHost (`MainActivity.kt:238`) reports the mini player's dock. A custom Layout places the background, ambient glow, details, video, mini catcher and mini controls from `PlayerSheetState.fraction` (`ui/PlayerSheet.kt:21`) during layout and draw only. The single PlayerView is moved by placement because a SurfaceView ignores graphics-layer transforms. While the full player covers the app, the app isn't drawn and is cleared from accessibility.
2. **Drag and Back:** drags settle in the fling direction when faster than twice the drag distance per second, otherwise to the nearer end (`ui/PlayerSheet.kt:63`). The docked player closes past 40% of the width or on an outward fling (`:70`). Back collapses with predictive progress to half-way (`ui/PlayerScreen.kt:129`). `ui/ScreenMotion.kt:89` ignores back gestures while the player covers the app. The springs are critically damped and snap when Remove animations is on. After testing the release build, the owner asked for collapse/expand to be twice as fast, so that spring is four times stiffer (`ui/PlayerSheet.kt`). Swiping away keeps MediumLow.
3. **Shared element:** `ui/PlayerShare.kt:26` tracks the opening card. The card's thumbnail (`ui/VideoCard.kt`) and the player's poster (`ui/PlayerScreen.kt:394`) share a key derived from the video ID, with matching 380 ms visibility transitions and bounds tween. The flight starts only after the layer's first frame (`PlayerShare.departed`), and the poster draws the card's decoded image. `PosterLifecycle` (`ui/PlayerScreen.kt:224`) keeps the poster until playback starts, fails or is paused, at most 20 s.
4. **Gestures:** `ui/VideoGestures.kt:49` is a View added to PlayerView's overlay frame (`ui/PlayerScreen.kt:328`), beneath the controller. It handles double tap on a side third (±10 s, accumulating within 700 ms), long press (2× until release), vertical swipe (fullscreen, exit fullscreen or collapse) and pinch (fit/zoom in fullscreen). Seek bubbles and the 2× pill are Compose. TalkBack custom actions on the video (`ui/PlayerScreen.kt:308`) are: seek back/forward 10 s, 2× on/off, fullscreen, zoom/fit and collapse.
5. **Chapters and storyboards:** `data/VideoTimeline.kt` normalizes chapters and picks the storyboard level (≤180 px wide, `*.ytimg.com` over HTTPS only). `data/NewPipeAnonymousSource.kt:125` maps them from the existing anonymous StreamInfo, failing soft and skipping live videos. Chapter gaps use `setExtraAdGroupMarkers` (`ui/PlayerScreen.kt:351`). `ui/SeekPreview.kt` draws the current-chapter label and the scrub preview (frame, chapter, time) above the time bar, placed from layout positions.
6. **Ambient mode:** `ui/AmbientGlow.kt:29` allows the glow only when enabled, in dark theme, on API 31+, outside fullscreen/PiP and outside Battery Saver (watched by broadcast). It blurs a 64 × 36 thumbnail, fading out towards the details. The comments/related tab row is transparent (`ui/PlayerCommunity.kt:41`).
7. **Video colours:** `ui/VideoColours.kt:28` runs MaterialKolor's Celebi quantizer, Score and SchemeContent on a 112 × 63 thumbnail on `Dispatchers.Default`. Grey images keep the app's colours. `withAccents` (`:37`) replaces only accent roles inside a nested MaterialExpressiveTheme over the details.
8. **Settings:** "Ambient mode" (API 31+) and "Colour player from video" under a new Player heading (`ui/SettingsScreen.kt:36`). Both are DataStore booleans in AppearancePreferences, on by default.
9. **Dependency:** `com.materialkolor:material-color-utilities:6.0.0`, approved by the owner. It does colour maths only, with no network access.

## Validation

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug --offline
./gradlew assembleSamples --offline
```

All pass. All 186 JVM tests pass, 23 of them new: VideoTimelineTest (7), PlayerSheetTest (7), VideoGesturesTest (6), VideoColoursTest (3). They cover chapter normalization, storyboard choice and frame maths, URL checks, sheet targets and settling, dismiss thresholds, 16:9 interpolation, seek accumulation and clamping, scrub placement, ambient conditions, card/player matching, and accent extraction with contrast checks. Lint lists 33 entries, all in unchanged code or dependency-version notices; there are no errors and no findings in changed code. No test contacts YouTube.

### Pixel 8 (signed-out samples installation)

Verified:
- **Opening from a search result:** the thumbnail flies from the card into the video area while the player fades in. Frame captures show no blank or jumping frames after the two fixes below.
- **Player visuals:** the ambient glow fades out behind the tabs. Accents take the thumbnail's colours. Chapter gaps show on the time bar, and the current chapter is labelled above it. Scrubbing shows a storyboard frame with the chapter and time above the thumb.
- **Seek and speed gestures:** double-tap seek accumulates ("+20s"; the position moved by about 20 s). Long-press gives 2× speed and returns to 1× on release.
- **Collapsing and expanding:** drag collapse and expand work, and tapping the mini player expands it. The Back key collapses without leaving search, and predictive edge-swipe back previews to about half-way. Swiping the mini player sideways closes it and stops playback.
- **Fullscreen and PiP:** swipe up enters landscape fullscreen and swipe down leaves it. PiP auto-enters on Home and keeps playing, then restores.
- **Background playback:** the mini player's play/pause toggles. Playback continues on Home with the foreground service and media notification.
- **Settings:** with the switches off there's no glow and the tabs stay brand-red. Light theme has no glow but keeps the accents.
- **Frame timing:** `dumpsys gfxinfo` over six collapse/expand drags reported 1.5% of frames missing their deadline (p50 5 ms, p90 12 ms, p99 48 ms). This is on the debuggable samples build, so it's pessimistic.

Not verified:
- **Pinch zoom:** `sendevent` is blocked by SELinux, so adb can't inject a two-finger gesture.
- **TalkBack custom actions:** not run with TalkBack on.
- **Casting:** no TV available.
- **Lock-screen controls.**
- **Tablet or navigation-rail layout.**
- **60 fps in a release build:** the release build installs over the owner's signed-in app, so it wasn't installed.

On the debug build, the first frame after tapping a card takes about 180–300 ms (the whole player composes). The flight now starts after that frame, so the tap feels slower to respond on debug builds rather than the animation jumping.

The samples installation was removed afterwards. The stay-awake setting and animator scale were restored. Screen captures stayed in `.tools/` because they show third-party video thumbnails.

## Intermediate failures and corrections

- The chapter label and scrub preview were placed from `getLocationInWindow`, which includes the controller's slide-in translation, so they appeared too low. They now use layout positions.
- The glow ended in a hard edge at the opaque tab row; the tab row is now transparent.
- One frame of the opaque "Loading video…" player showed before the fade began; the fade's initial alpha is now created during composition.
- **Flight held at the card, then jumping:** instant visibility transitions ended before the bounds animation attached. Then, separately, the layer's slow first frame stalled the flight clock. Fixed with matching 380 ms transitions on both ends and by starting the flight after the layer's first frame.
- **Flight briefly blank:** the poster requested the thumbnail again at another size, so it was blank until loaded. It now draws the card's decoded image.

## Deviations from the brief

- Touch handling is a View inside PlayerView's overlay, not a Compose overlay, so the Media3 controller keeps its own touch handling. Feedback is still Compose.
- Chapters are drawn as Media3 ad-group markers rather than a custom time bar.
- Back in fullscreen collapses the player (fullscreen follows rotation).
- Settings opened from the description sheet collapses the player first.
