# UI refresh — Phase 5

## Implementation

Source paths below are relative to `app/src/main/java/dev/quietube/`.

1. **Dependencies:** `app/build.gradle.kts` pins Compose BOM `2026.09.00` and Material 3 `1.5.0-rc01`; version is now 0.5.5 (16). The owner explicitly approved the release candidate because the latest stable Material 3, 1.4.0, does not expose the required Expressive components. Activity, Navigation and Lifecycle did not require upgrades. No analytics, logging, Play Services, Firebase or third-party network integration was added. Expressive loading opt-ins are file-local.
2. **Theme and motion:** `ui/Theme.kt:136` uses MaterialExpressiveTheme with MotionScheme.expressive(), retaining the complete brand/wallpaper schemes, typography, shapes and appearance preferences. ScreenMotion and its back-preview timings are unchanged.
3. **Connected ratings:** `ui/EngagementButtons.kt:20` uses ButtonGroup with connected ToggleButton shapes, icons and counts, filled checked states and adaptive overflow. Like/Remove like and Dislike/Remove dislike accessibility labels remain. Signed-out controls remain disabled with “Sign in to rate”. Targets have a 48dp minimum height.
4. **Subscription:** `ui/EngagementButtons.kt` uses primary filled styling for Subscribe and tonal styling for Subscribed. ToggleButton supplies the shape morph; animateColorAsState uses the Expressive fast-effects spec for the colour morph. Pending requests disable repeat taps. `MainActivity.kt:108` collects one Confirm haptic event per accepted mutation, including Retry and Undo, rather than firing on recomposition, refresh or rollback.
5. **Loading:** `ui/AppLoadingIndicator.kt:10` replaces indeterminate circular spinners in the player overlay, mini player, feed/search load-more and cast discovery. `ui/RefreshBox.kt:11` supplies PullToRefreshDefaults.LoadingIndicator for feeds and comments. `ui/VideoCardPlaceholder.kt:39` shares a live animation-setting observer; disabled animations use static Expressive shapes with indeterminate loading semantics. Existing linear progress displays remain.
6. **Optimistic actions:** `account/actions/EngagementViewModel.kt:88` updates rating/subscription state before launching the request, serializes actions, restores the previous metadata on failure and offers Retry. Successful unsubscribe offers Undo. The ViewModel remains the only action-state owner; snackbar actions carry notice IDs and cannot cross a video/account change. Session revisions also block taps before the session collector resets the old UI. Numeric like counts update locally; formatted/unknown counts and third-party dislike estimates are preserved.
7. **API migration:** list items, community tabs, bottom-sheet state and search use the current non-deprecated APIs. `ui/SearchScreen.kt:25` uses TextFieldState, SearchBarState and ExpandedFullScreenSearchBar while retaining the search route, history, results, paging and error states. Typing flows from the field to the ViewModel; programmatic searches update the field directly. This avoids a feedback loop that dropped characters. The collapsed bar applies the status-bar inset explicitly.
8. **Review samples:** `app/src/samples/` renders the production controls using synthetic state in a separate `dev.quietube.samples` installation. The sample activity reads no account and makes no network calls. It is excluded from debug/release. The normal app's sign-in data was retained.

## Validation

```sh
./gradlew assembleDebug assembleSamples testDebugUnitTest lintDebug
```

Final combined build: `BUILD SUCCESSFUL in 35s` (102 tasks: 25 executed, 77 up-to-date). All 143 offline JVM tests pass, zero failures/errors. Lint has zero errors, the same 41 existing warnings, and no new findings against the pre-phase XML report. Compilation has no deprecation warnings. `git diff --check` passes.

The 13 new tests cover synchronous optimistic state, accepted results, exact rollback and retry, active-rating removal, like/dislike count handling, unsubscribe/undo, duplicate-tap serialization, sign-out invalidation, non-cancellable stale responses, notice invalidation, account-revision races, and one haptic event per accepted action. No JVM test contacts YouTube.

### Pixel 8

- Installed the debug APK over the existing app without clearing its account/data.
- Used the separate signed-out sample installation for anonymous playback: the test video played, MediaSession reported PLAYING, PiP reported a pinned task, and Back returned to a playing mini player above navigation. Playback was paused and closed afterward.
- Search expanded, retained exact injected text (`blender`, then `quietube`), submitted with Enter and collapsed to results with navigation visible. The final collapsed input bounds began at y=132 rather than under the status bar. Edge-back committed from search to Home. This checks the gesture's committed destination, not frame-by-frame predictive preview quality.
- Inspected native light/dark component samples. The accessibility tree contains checked toggle parents, disabled signed-out controls and loading semantics; the rating targets measured 126px at the device's 2.625 density (48dp).
- Set animator duration scale to zero temporarily. The two loading shapes had zero differing pixels in their region across separate captures. The original setting was restored. Reading the setting directly fixed an initially stale ValueAnimator cache during live setting changes.
- The original animator setting (unset) and plugged-in stay-awake setting (0) were restored. The final debug APK cold-started successfully in the normal installation.
- Screenshots below contain synthetic state only. No signed-in account screenshot is included in the repository.

### Screenshot samples

| Light | Dark |
| --- | --- |
| ![Light component samples](phase5-components-light.png) | ![Dark component samples](phase5-components-dark.png) |

The isolated gallery shows unselected/liked/disliked and signed-out ratings, Subscribe/Subscribed, and both loading variants. These are native captures of production components with synthetic state, not full signed-in player screens.

## Intermediate failures and corrections

Compiler/API migration diagnostics included these verbatim message excerpts (local filesystem prefixes omitted):

```text
No value passed for parameter 'consume'.
This material3 API is experimental and is likely to change or to be removed in the future.
This annotation is not repeatable.
Attribute application@label value=(YouPoop UI samples)
Unresolved reference 'message'.
```

The snackbar call now names its consume callback, loading opt-ins are file-local, the existing sheet opt-in is combined correctly, and the samples manifest explicitly overrides its label. A transient signature-edit error was corrected before the passing final build. The dependency upgrade did not require changes to playback or navigation architecture.

Device automation also caught dropped search characters and an animated contained loader after a live Remove animations change. The text-state feedback loop and cached animation-setting read were corrected and the checks repeated. Initial capture scripts reported:

```text
ModuleNotFoundError: No module named 'PIL'
AssertionError
```

The unavailable image-analysis package was replaced with native bitmap comparison. An early reduced-motion comparison failed before the observer fix; the final comparison reports zero differing pixels. Early search assertions also ran during an app replacement or before focus was ready; the final sequential checks wait for the expanded field and pass. Scratch logs, XML and scripts remain ignored.

## Scope and remaining acceptance

The release-candidate dependency is the only deviation from the brief's stable-only request, explicitly approved by the owner. No Phase 6 work is included. Actual YouTube rating/subscription mutations were not performed; success/failure/retry/undo and haptic event counts were verified with offline fakes. Physical haptic feel, a full TalkBack session, large-font layouts, casting, background/lock-screen playback, tablet/foldable layouts and predictive-back frame quality remain manual acceptance checks. The PiP/playback smoke test does not substitute for a full regression pass.

No further owner decision is needed to review this phase. The requested PR targets `main`; Phase 6 has not started.
