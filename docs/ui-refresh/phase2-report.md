# UI refresh — Phase 2 report

## Changes by requirement

Paths below are relative to `app/src/main/java/dev/quietube/` unless stated otherwise.

1. **Watched progress:** `ui/Format.kt:36` calculates a clamped fraction, hides unknown/live/<2% progress, and fills the bar from 95%. `ui/VideoCard.kt:41` draws the clipped 3dp track/fill. `data/DefaultVideoRepository.kt:30` reads local progress in batches for feed, search, playlist and related results; local progress takes precedence over account progress. `playback/ResumeDatabase.kt:19` adds the read query without changing the schema. Progress refreshes when results load.
2. **YouTube links:** `links/YouTubeLinks.kt:17` validates scheme, exact hosts, userinfo, port, ID/path and timestamps, returning a canonical watch URL. `:43` extracts a video link from shared text. `app/src/main/AndroidManifest.xml:19` registers VIEW and SEND filters, explicitly without domain auto-verification. `MainActivity.kt:226` handles launch/new intents and invalid-link feedback; the existing savedInstanceState guard and debug-only extra remain. `playback/PlaybackViewModel.kt:44` separates the timestamp from the extraction URL. `playback/PlaybackService.kt:108` forwards it; `playback/ResumeDatabase.kt` applies an explicit timestamp ahead of saved resume and keeps live/resume behaviour.
3. **Snackbars:** `MainActivity.kt:108` observes and acknowledges account/engagement/history-sync/cast messages through `ui/StatusMessage.kt:23`; `MainActivity.kt:155` provides the Scaffold host. Cast dialogs use the same host while open so their messages remain visible. `account/AccountViewModel.kt:46`, `account/actions/EngagementViewModel.kt:20`, `cast/CastViewModel.kt:22` and `playback/PlaybackViewModel.kt:49` consume only the matching message. Rating refresh preserves its success message until acknowledgement. Inline transient text was removed from Account/Player; persistent errors stay inline.
4. **Shared status states:** `ui/StatusMessage.kt:13` is used for feed/search errors and empty results, comments errors/empty/disabled and related-empty. Feed authentication expiry retains Open account.
5. **Skeletons:** `ui/VideoCardPlaceholder.kt:22` provides the common feed/search placeholder. The shimmer observes the system animation-scale setting and removes its infinite transition when animations are disabled. Existing pull refresh, paging, retry and prefetch flows remain in place.
6. **Accessibility:** `ui/VideoCard.kt:26` merges the card and clears the visual descendants' separate semantics, using `ui/Format.kt:41` for the full label. Thumbnail images are decorative. `ui/PlayerScreen.kt:114` uses a clickable title row with Button role and Expanded/Collapsed state description.

## Commands and results

Commands used the workspace JDK 17 via JAVA_HOME.

```sh
./gradlew assembleDebug
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Both passed. Final result: 115 JVM tests, zero failures; lint zero errors and 41 existing warnings, with zero new findings against the Phase 1 report. `git diff --check` passed. The final APK was installed with `adb install -r`, preserving the existing account/data.

Added tests cover URL forms and hostile/ambiguous inputs, timestamps and overflow, shared-text extraction, watched thresholds and extreme values, accessibility-label formatting, local-vs-account progress and explicit-start-vs-resume/live behaviour. Automated tests use no live network.

Intermediate builds succeeded. A deprecated Comment icon warning was fixed with its AutoMirrored version. Lint initially warned about missing autoVerify attributes; both video-link filters now explicitly set autoVerify=false. No unresolved command failure.

## Pixel 8 checks

- VIEW of `https://youtu.be/aqz-KE-bpKQ?t=90` opened playback; media session reported PLAYING at 92,905 ms.
- SEND text containing the same video with start=120 opened playback; media session reported PLAYING at 123,683 ms.
- A lookalike `youtube.com.evil.com` URL produced exactly “Not a YouTube video link” in the UI hierarchy, without starting that URL.
- Feed displayed account progress; searching the previously watched test video displayed local Room progress (41%). Both light and dark result screens were inspected.
- UI Automator exposed one accessibility node per result with title, channel, duration, views, age and watched percentage; no separate thumbnail/title nodes appeared beneath it. TalkBack's spoken output itself was not tested.
- Search remained on its results through theme changes. The test did not sign out, rate, subscribe or start a cast.

## Screenshots

Device captures:

- Search, light
- Search, dark
- Feed progress, dark
- Invalid-link snackbar, light

## Deviations, remaining checks and owner decisions

No Phase 2 requirement was intentionally deferred. Android's actual Open with/default-app chooser and the Chrome/YouTube share-sheet UI were not exercised; VIEW/SEND were delivered with adb to test the same exported entry points. Manual TalkBack speech, Remove animations, skeleton motion, every empty/error variant, on-device related progress, cast/account snackbar flows and playback regression checks (PiP/fullscreen/background/casting) remain unverified in this phase.

No owner decision is needed for Phase 2. Phase 3 has not started. Nothing committed or pushed.
