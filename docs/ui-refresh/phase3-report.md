# UI refresh — Phase 3 report

## Changes by requirement

Paths are relative to `app/src/main/java/dev/quietube/` unless stated otherwise.

1. **Settings:** `ui/SettingsScreen.kt:20` adds Appearance, Playback, Account sync, Third-party services and About sections using Material list rows, radios and switches. Appearance uses its existing independent DataStore; history/dislike settings use the existing SyncPreferences. Return YouTube Dislike's disclosure includes the video ID and IP address. SponsorBlock/DeArrow have non-interactive unavailable rows, with no integration. `MainActivity.kt:179` wraps the new route in ScreenCard. Account, the feed/search avatar menu (`ui/AccountAvatar.kt:22`) and description sheet open it. Both sync switches were removed from the player.
2. **Quality sheet:** `ui/PlayerSheets.kt:23` uses ModalBottomSheet, selectable ListItem/RadioButton rows, the Auto resolution-cap description and actual playing height. Selection calls the existing quality callback and dismisses; no checkmark characters or quality AlertDialog remain.
3. **Cast sheet:** `ui/PlayerSheets.kt:45` shows scanning/sending progress, a TV ListItem and separate YouTube-app/direct playback rows, Search again and the explanation. Discovery and playback callbacks are unchanged. The API-37 ACCESS_LOCAL_NETWORK launcher remains in `ui/PlayerScreen.kt`; no permission or network policy changed.
4. **Description sheet:** `ui/PlayerSheets.kt:69` starts partially expanded and includes title, channel, views/date, quality, subscription/refresh actions, Settings and selectable description text. The 220dp inline panel is removed from `ui/PlayerScreen.kt`. `data/VideoDescription.kt:5` converts the extractor description to plain text on the existing extraction IO path (`data/NewPipeAnonymousSource.kt`), with no new request or WebView. `data/PlaybackDetails.kt:24`, `playback/PlaybackState.kt:22` and `playback/PlaybackService.kt:188` carry it in memory. `ui/Format.kt:45` formats ISO publication dates for the current locale and retains relative-date fallbacks. Snackbar hosting follows the active sheet so subscription/history/cast feedback stays visible.
5. **Account:** `ui/AccountScreen.kt:33` uses LargeTopAppBar, connected avatar/name/method and action rows. Signed-out layout has a primary Google action and secondary import/device-code actions; explanations expand on demand. `:74` confirms sign-out before invoking existing cleanup. `:88` puts pending device codes and copy/browser/cancel controls in a Card. Sensitive clipboard marking and Chrome-then-fallback launch are retained. Account/session ViewModel behaviour is unchanged.
6. **Playlists:** `ui/FeedScreen.kt:78` uses ListItem with a leading 16:9 cover. Existing click, paging and playlist-back callbacks remain in place.

## Commands and results

With JAVA_HOME pointing to `.tools/jdk-17.0.21+9/Contents/Home`:

```sh
./gradlew assembleDebug
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Both passed. The final combined run reported:

```text
BUILD SUCCESSFUL in 46s
60 actionable tasks: 20 executed, 40 up-to-date
```

All 120 offline JVM tests passed, zero failures. Lint: zero errors, 41 existing warnings, zero new findings compared with the Phase 2 report. `git diff --check` passed. No dependency, authenticated-client, persistence-schema or credential-storage changes.

New tests: `app/src/test/java/dev/quietube/data/VideoDescriptionTest.kt` covers missing/plain/HTML descriptions, line breaks/entities and script removal; `app/src/test/java/dev/quietube/ui/PublishedDateTest.kt` covers ISO timestamps, plain dates, relative/invalid dates and nulls.

The final debug APK was installed with `adb install -r`, preserving the owner's session and preferences. No build or test command failed. UI Automator occasionally could not become idle while player controls were animating; the capture helper then could not find its target. Exact diagnostics:

```text
ERROR: could not get idle state.
Target not found: Play on TV
```

The affected capture was retried using inspected screen coordinates; it was not a Gradle or app crash. A stale/delayed automation target also caused an unintended Subscribe tap during capture. It was reversed immediately after it was noticed. A fresh UI dump confirmed both “Unsubscribed on YouTube.” and the restored “Subscribe” action. No claim is made that UI Automator exercised every interaction.

## Pixel 8 verification

- Avatar menu opens Settings. Appearance changes apply live; the saved mode survived APK replacement.
- Account shows the connected profile and method, compact action list and expandable sign-in information. Sign-out opens its confirmation; Cancel preserved the session. Actual sign-out was not performed.
- Description opens partially expanded, shows extractor text, channel and readable localized publication date. The player below the video has title, ratings, cast status/errors and Comments/Related, with no inline settings panel.
- Quality shows Auto, selected state, available resolutions and actual playing height. Selecting 720p dismissed the sheet. The media-session position was 193,644 ms before selection and 201,892 ms afterward during buffering; playback then resumed and advanced to 242,764 ms, rather than restarting.
- An unintended subscription tap during automation was reversed and the original unsubscribed state verified with a fresh UI dump.
- Cast scanning found “Hisense VIDAA TV” and displayed both “YouTube app · HD, with ads” and “Direct from phone · HD, no ads · experimental”. No cast was started.
- Light/dark Account, Settings and player-sheet rendering inspected. Playback was paused after testing.

## Screenshots

Captures are in `.tools/ui-refresh-phase3/`:

- Settings, light / dark
- Account, light / dark
- Description, light / dark
- Quality, light / dark
- Cast scanning, light / dark, discovered TV/options
- Sign-out confirmation

## Deviations and remaining checks

No implementation requirement intentionally deferred. Full description wiring was straightforward and is included. Signed-out one-screen fit and pending device-code/card/clipboard/browser interactions were not verified on the owner's device because its signed-in session was retained. Playlist-row device acceptance, settings sync-toggle persistence, actual casting, sign-out cleanup, large fonts, TalkBack, Remove animations, predictive-back gestures, PiP/fullscreen/background regression checks remain unverified in this phase. Existing tests and code paths continue to pass; that does not replace those manual checks.

No owner decision is needed for Phase 3. Before Phase 4, the prompt requires confirmation of its proposed Home / Subscriptions / Library navigation and adaptive rail design. Phase 4 has not started. Nothing committed or pushed.
