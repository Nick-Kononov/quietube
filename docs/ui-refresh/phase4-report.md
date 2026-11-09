# UI refresh — Phase 4 report

## Changes by requirement

Paths below are relative to `app/src/main/java/dev/quietube/` unless stated otherwise.

1. **Navigation structure:** `ui/AppNavigation.kt:13` uses Material 3 NavigationSuiteScaffold for Home, Subscriptions and Library. `ui/Navigation.kt` defines the routes, Library feeds and 600dp rail threshold; `MainActivity.kt:155` uses the actual window width. `ui/LibraryScreen.kt:20` exposes History, Watch Later, Liked videos and Playlists in one more tap and provides a signed-out sign-in prompt. Subscriptions has the same signed-out treatment in FeedScreen. The bar/rail hides on the player; expanded search temporarily hides navigation and the dock to fill the window. `ui/MiniPlayer.kt` avoids adding a second navigation inset above the phone's bottom bar.
2. **Feed state and back:** `account/FeedSnapshots.kt:7` and `account/FeedViewModel.kt:69` retain each feed/playlist's items, cursor and retry mode in memory for the current account. Generation/revision checks reject stale results. Switching tabs closes search/account/settings overlays; Library consistently opens its list. `MainActivity.kt:97` and `:197` keep separate saveable UI state for each feed/playlist outside the lifetime of an individual navigation entry, including when Back removes a tab entry. `FeedViewModel.kt:56` restores the selected feed/playlist metadata from the existing feed DataStore. `ui/FeedScreen.kt:38` refills needed pages before attaching a deeply restored list so its saved position is not clamped by an initial short page; empty/error/paging-stop states still stop automatic requests. Only the active route can issue paging, refresh or prefetch callbacks. Re-selecting a feed scrolls to the top, reveals the app bar and refreshes; Library's static list only scrolls to the top. All navigation entries retain ScreenCard and the existing ScreenMotion/BackPreview implementation.
3. **App bars:** `ui/FeedScreen.kt:66` uses TopAppBar with wordmark/feed label, search and avatar, with enterAlways nested scrolling and the existing PullToRefreshBox. Opened playlists use their title and the existing playlist back callback. `ui/LibraryScreen.kt` also uses a scrolling top bar. `ui/AccountScreen.kt:40` and `ui/SettingsScreen.kt:23` use LargeTopAppBar with exitUntilCollapsed nested scrolling.
4. **Search:** `ui/SearchScreen.kt:26` replaces the header/text field with M3 SearchBar and its InputField, expanding across the phone window. Recent-search rows, Clear history and the existing suggestion are available while expanded; the original cards, skeletons, paging and persistent error/empty states remain in results. The short empty-state copy has no fixed hero font size. Focus moves to the screen container on submission/collapse, preventing keyboard focus from reopening the bar. The existing search route and predictive route motion remain.
5. **Recent searches and privacy:** `ui/SearchHistory.kt:14` trims/deduplicates queries, caps them at 20 and limits each to the existing 500-character search limit. `:36` stores them in the local search_history DataStore. `ui/SearchViewModel.kt` owns its state, records explicit searches and saves unfinished drafts; restoring a submitted query reloads results without repopulating cleared history. `account/AccountViewModel.kt:186` clears search history after existing sign-out cleanup. Appearance remains separate and survives sign-out. No account credentials or signed stream URLs enter these stores.
6. **Dependency:** `app/build.gradle.kts` adds material3-adaptive-navigation-suite, resolved to 1.3.2 through the existing Compose BOM. The BOM and playback/authentication dependencies were not upgraded. No Play Services, Firebase, analytics or HTTP logging dependency was introduced.

## Verification

Using the workspace JDK 17:

```sh
./gradlew assembleDebug
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Final combined result:

```text
BUILD SUCCESSFUL in 22s
60 actionable tasks: 16 executed, 44 up-to-date
```

All 130 offline JVM tests passed, zero failures. Lint: zero errors, 41 existing warnings and no new findings against Phase 3. `git diff --check` passed. New tests cover route/top-level mapping, rail threshold, independent feed/playlist snapshots and cursors, cache clearing, bounded/deduplicated/corrupt recent history, draft restoration, and history clear/restore behaviour. No automated test contacts live YouTube.

## Pixel 8 checks

- All six feeds were reached through the three destinations and four Library rows. History, Watch Later, Liked videos and Playlists opened through Library.
- After scrolling Home, switching to Subscriptions and back restored the same first visible card and its bounds: `[0,132][1080,855]`. Re-selecting Home returned its list/app bar to the top.
- After scrolling Subscriptions, the app was backgrounded and its debug process killed without clearing app data. A new process was confirmed. Once the feed reloaded, the same first visible card and bounds returned: `[0,132][1080,181]` before and after. This exercised saved navigation/list state and the existing encrypted session's restoration.
- A temporary 2400×1080 window override displayed the navigation rail with Library selected. The original display size was restored afterward. This is a wide-window check, not testing a physical tablet/foldable.
- Search expanded to the full phone window with keyboard and recent queries. Touch/on-screen-keyboard submission and injected Enter were exercised; results collapsed correctly after the focus fix. Recent history survived app replacement; Clear history was exercised. Automatic query restoration does not re-add cleared history in the JVM tests.
- The test video played, returned to a mini-player docked above navigation, and reopened in the full player. PiP reported a pinned YouPoop task. The test media was paused and closed afterward.
- Light and dark Library/search rendering were captured. The original appearance selection (System), physical display size (1080×2400) and stay-awake setting (0) were restored. Test search history was cleared.

## Screenshots

- Home, dark
- Library, dark / light
- Wide-window rail
- Expanded search, dark / light
- Search results, dark / light
- Mini-player above navigation
- Playlists

## Intermediate findings and diagnostics

No Gradle build or unit-test command failed. Two new lint findings were corrected rather than suppressed:

```text
Using Configuration.screenWidthDp instead of LocalWindowInfo.current.containerSize
Creating a MutableState object with a mutable collection type
```

The API-inspection command initially used the wrong platform class name:

```text
Error: class not found: androidx.compose.material3.SearchBarKt
```

The Android artifact's SearchBar_androidKt and pinned source were inspected instead. A search focus/refocus issue found on the device was fixed and verified. Temporary boolean-only focus diagnostics were removed from the source.

The native capture helper encountered selected tabs advertised as non-clickable, an automatically locked phone, and captures taken before asynchronous UI/data arrived. Exact helper diagnostics included:

```text
Expected one target Home, got 0
Expected one target History, got 0
Expected one target Clear search, got 0
Expected one target Close player, got 0
YouPoop is not the focused app; no interaction performed.
IndexError: list index out of range
StopIteration
```

These were capture timing/selector conditions rather than app crashes. The helper now uses fresh app-only snapshots, accepts selected tab targets, and aborts instead of acting on stale or other-app UI. The process-restore comparison was repeated after data arrived and passed.

## Remaining checks and scope

No implementation requirement intentionally deferred. Signed-out prompt conditions and the sign-out hook were code-reviewed; recent-history clearing/restoration is covered by JVM tests; the owner's signed-in session was not erased for device testing. Manual predictive-back gesture acceptance, font scaling, Remove animations, a physical tablet/foldable, real hardware rotation and a full casting/background-playback regression pass remain unverified. No actual cast, rating or subscription action was performed in this phase.

The final debug APK was installed with the existing account/data retained. Temporary focus logging is absent from the final source.

No owner decision remains for Phase 4. Phase 5 has not started. Nothing committed or pushed.
