# YouPoop UI/UX refresh: Material 3 / Material You and modern player UX

You are working on **YouPoop** (package `dev.quietube`), a private, GPL-3.0-or-later Android YouTube client
in Kotlin + Jetpack Compose. Read `README.md` first: it records the shipped phases, security and privacy
rules, and build commands. This task brings the UI fully onto Material 3 / Material You (including M3
Expressive) and adds the modern video-app UX patterns listed below, without weakening any existing
privacy or security behaviour.

Work **phase by phase**. Each phase must build, pass tests and lint, and be usable on its own. After each
phase, stop and report (see "Reporting"). Do not start the next phase until told to.

---

## Ground rules

### Code style (match the existing code)
- Compact Kotlin: long lines, few blank lines, small composables, `internal` for UI helpers in `dev.quietube.ui`.
- Comments are sparse, one line, and explain **why** (constraints, platform quirks), never what.
  Keep every existing comment that still holds.
- State lives in the existing ViewModels (`SearchViewModel`, `FeedViewModel`, `AccountViewModel`,
  `PlaybackViewModel`, `CommentsViewModel`, `EngagementViewModel`, `CastViewModel`), wired in
  `MainActivity.kt`. Composables stay stateless apart from UI-only state (`remember`/`rememberSaveable`).
- Screens take lambdas, not ViewModels (see `FeedScreen`, `PlayerScreen` signatures). Keep that.
- Hilt for DI, DataStore for preferences, Room for local progress. Add no new DI or persistence framework.

### Hard constraints
- **No** Google Play Services, Firebase, analytics, crash reporting or HTTP logging dependencies.
- Every new network integration (SponsorBlock, DeArrow) is **off by default**. When enabled it sends only
  what is disclosed in the UI. It follows the `DislikeEstimates` pattern in
  `account/actions/SyncPreferences.kt`: its own OkHttp client with `CookieJar.NO_COOKIES`, no redirects,
  a call timeout, a capped response body, video-ID regex validation and a small in-memory cache.
  Prefer the hash-prefix endpoints (SHA-256 prefix of the video ID) so the exact video ID is not sent.
- Never send account cookies or authorization headers anywhere new. Do not touch the authenticated
  OkHttp client's allow-list.
- Signed stream URLs stay in memory only: never in Room, DataStore, logs or intents.
- Keep the predictive-back implementation in `ui/ScreenMotion.kt` (`BackPreview`, `ScreenCard`) working.
  Any new navigation must go through it, or a reviewed replacement that looks the same.
- Keep PiP (`MainActivity.updatePip`, source-rect hint, auto-enter), fullscreen/immersive handling,
  `MediaSessionService` playback, casting, comments paging, feed continuation paging and prefetching
  (`PrefetchVisibleVideos`) behaving exactly as now.
- minSdk 26. Gate API 31+ features (dynamic colour, `RenderEffect`, rounded-corner insets) with
  `Build.VERSION.SDK_INT` checks and give a sensible fallback.
- Respect the system "Remove animations" setting and font scaling. All touch targets must be ≥ 48dp.

### Build and verification (every phase)
```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```
- Add JVM unit tests for all new pure logic (URL parsing, hash prefixes, segment math, colour/scheme
  selection, formatting). Existing tests live in `app/src/test/java/dev/quietube/**`.
  Put UI-free logic in plain functions so it is testable without Compose.
- Lint must report no new warnings or errors.
- If a device or emulator is available, install the debug APK and smoke-test the phase's acceptance
  criteria. Otherwise say clearly that on-device checks were not run. The owner tests on a Pixel 8.
- Update `README.md` for any behaviour or privacy-relevant change, keeping its tone.
  Only claim what was verified.

---

## Current state (verified facts)

- Compose BOM `2025.08.01` (material3 1.3.x). M3 Expressive APIs need a newer BOM (Phase 5).
- `ui/Theme.kt`: `QuietubeTheme` is dark-only. `darkColorScheme(...)` sets only ~9 roles (primary
  `Red = #FF0033`, background/surface `#101215`, surfaceVariant `Panel = #1B1E23`, secondaryContainer,
  onSurface, onSurfaceVariant). All other roles fall back to M3 baseline purple, which is why
  `MiniPlayer` (`surfaceContainerHigh`) looks purple-grey. There is no custom `Typography` or `Shapes`.
  `Wordmark` uses condensed Roboto plus `R.drawable.ic_quietube`.
- `res/values/styles.xml`: `Theme.Quietube` extends `android:style/Theme.Material.NoActionBar`
  with a hard-coded dark `windowBackground` and `windowLightStatusBar=false`.
- `res/mipmap-anydpi`: the adaptive icon has background `#C10800` and a foreground, but **no
  `<monochrome>` layer**.
- Hard-coded visuals that bypass the theme: `Panel`, `Red`, `Color.Gray`, `Color.Black` in `VideoCard`
  and `SearchScreen`; `RoundedCornerShape(16/18/5/50.dp)`; `fontSize = 38.sp` hero text in `SearchScreen`.
- Navigation (`MainActivity.kt`): NavHost routes `home`, `search`, `account`, `player`. Each screen is
  wrapped in `ScreenCard` for predictive back. `MiniPlayer` is drawn below the NavHost when a video is
  active and the player route is not visible.
- `FeedScreen`: a hand-rolled header `Row` (back for playlists, wordmark, search, avatar), then a
  horizontal row of six `FilterChip`s (Home, Subscriptions, History, Watch Later, Liked videos,
  Playlists; non-Home ones are disabled when signed out), then `PullToRefreshBox` + `LazyColumn` with
  playlist rows (hand-made `Row`), `VideoCard`s, inline error/empty/partial texts and "Load more".
  A playlist opens as screen state, with Back handled by `BackHandler`.
- `SearchScreen`: a hand-rolled header, `OutlinedTextField`, a marketing hero when no search has run
  (three `SuggestionChip` topics), a results list with a skeleton while loading, an error state with an
  icon, and a "Try searching:" suggestion.
- `VideoCard`: 16:9 thumbnail (prefers `hq720.jpg`, falls back), duration/LIVE badge, title, and a meta
  line. **`Video.resumePositionMs` exists but is never drawn.** The thumbnail `contentDescription`
  repeats the title.
- `PlayerScreen`: Media3 `PlayerView` (its own controller) with a Compose overlay (back, cast, PiP,
  fullscreen) that fades with the controller. Below it: the title is a `TextButton` toggling a 220dp
  details panel (channel, quality `TextButton`, "Background audio enabled", subscribe/refresh, **"Sync
  watch history" switch, "Estimate dislikes" switch** and the RYD disclosure). Like/dislike are two
  `FilterChip`s. Cast status row, inline error and retry. `PlayerCommunity` has a `TabRow` + `HorizontalPager`
  (Comments / Related). The quality and cast pickers are `AlertDialog`s; quality marks the selection
  with a "✓" character.
- `AccountScreen`: a long scrolling column of paragraphs and 3–5 stacked buttons (device-code sign-in,
  web sign-in, cookies.txt import, sign out) plus explanatory texts.
- `SyncPreferences` (DataStore `sync_preferences`) holds `history` and `dislikeEstimates`.
  Per the README, UI preferences are cleared on sign-out.
- `AndroidManifest.xml`: `MainActivity` has only the MAIN/LAUNCHER intent-filter. `readIntent` accepts a
  `video_url` extra **in debuggable builds only** (exported-activity safety). Keep that restriction.
- NewPipeExtractor `v0.27.0` is available (`StreamInfo` exposes stream segments/chapters and preview
  frames/storyboards). The app does not use them yet.

---

## Phase 1: Theme foundation and visual consistency

1. **Full colour scheme from the brand seed.** Replace the partial `darkColorScheme` with complete
   light **and** dark schemes generated from seed `#FF0033` (Material Theme Builder output pasted as
   constants is fine; or add `com.materialkolor:material-kolor` if it is small and has no transitive
   Play Services, and justify it). Every role must be defined, so no baseline purple remains. Keep the
   current near-black dark background feel (`#101215`-like surfaces) and pure-ish brand red for primary
   actions. Check contrast: body text ≥ 4.5:1 and large text/icons ≥ 3:1 on their containers.
2. **Theme mode setting.** Add an appearance preference: System (default) / Light / Dark.
3. **Dynamic colour (opt-in).** A "Use wallpaper colours" toggle (API 31+ only; hidden below 31) using
   `dynamicLightColorScheme`/`dynamicDarkColorScheme`. Default off: brand red stays default.
4. **Appearance preferences survive sign-out.** Store theme mode and dynamic colour in a separate
   DataStore (e.g. `appearance_preferences`), *not* `sync_preferences`, and keep them out of the
   sign-out wipe. Document this in the README's DataStore sentence.
5. **XML theme.** Switch `Theme.Quietube` to a DayNight-aware parent (e.g.
   `android:Theme.Material.Light.NoActionBar` with a `values-night` override, or AppCompat/Material
   Components DayNight only if already on the classpath; prefer no new dependency). The window
   background and status-bar icon colour must follow the active mode, with no white or black flash on
   cold start. Look at the Android 12+ splash screen (`android:windowSplashScreen*`) and make it match.
   No splash library unless already present.
6. **Typography and shapes.** Define `Typography` (keep Roboto; the wordmark keeps its condensed font)
   and `Shapes` in `Theme.kt`. Replace every hard-coded size, shape and colour listed in "Current state"
   with theme tokens: e.g. hero → `displaySmall`/`headlineLarge`, thumbnails → `shapes.large`,
   duration badge → `shapes.extraSmall` with a scrim colour token, pills → `shapes.extraLarge`/`CircleShape`.
   Remove `Panel`/`Red`/`Background` usage outside `Theme.kt` (use `surfaceContainer*`, `primary`, etc.).
   The video surface stays black in both modes.
7. **Themed icon.** Add a `<monochrome>` layer to the adaptive icon (`mipmap-anydpi`) derived from
   `art/logo.svg` as a single-colour vector drawable that fits the 72dp safe zone.
8. **Light-mode audit.** Every screen, dialog, chip, badge, scrim, comment and progress indicator must
   read correctly in light mode. The player overlay icons stay white over video.

Acceptance: no baseline purple anywhere; switching System/Light/Dark/dynamic restyles the app live
without restarting playback; the themed icon shows on a Pixel launcher with themed icons on; appearance
settings persist across sign-out; `grep` finds no `Color(0x…)`/`RoundedCornerShape(` outside
`Theme.kt` except documented exceptions (video black, overlay white).

## Phase 2: Quick UX wins

1. **Watched-progress bar on thumbnails.** In `VideoCard`, when `resumePositionMs` and `durationSeconds`
   are known (not live), draw a thin (≈3dp) bar along the thumbnail's bottom edge: `primary` fill over
   a translucent track, clipped to the thumbnail shape. Put the fraction in a pure, tested function
   (clamp 0–1; hide if < 2% or unknown; full bar if ≥ 95%).
2. **Open YouTube links in the app.**
   - Add `ACTION_VIEW` intent-filters (with `BROWSABLE`/`DEFAULT`) for `https` on `youtube.com`,
     `www.youtube.com`, `m.youtube.com` (paths `/watch`, `/shorts/`, `/live/`) and `youtu.be`.
     Use `autoVerify` only if it can't misfire; the app can't own these domains, so the user picks it
     in "Open with".
   - Add `ACTION_SEND` `text/plain` so "Share → YouPoop" works. Extract the first YouTube URL from
     the shared text.
   - New pure parser `parseYouTubeLink(String): String?` returns a **canonical**
     `https://www.youtube.com/watch?v=<11-char id>` (keep a `t=`/`start` timestamp if present, as a
     start position). It rejects every other host, scheme, ID shape and playlist-only link
     (playlists out of scope). Cover it with unit tests, including malicious inputs (`youtube.com.evil.com`,
     `javascript:`, userinfo `@` tricks, overlong IDs).
   - Handle it in `readIntent`/`onNewIntent` alongside the existing logic. The existing debug-only
     `video_url` extra stays debug-only. Don't replay on recreation (keep the `savedInstanceState` guard).
   - These are user-initiated, so playback may start, but history reporting follows the existing
     "Sync watch history" setting as for any other play.
3. **Snackbars for short-lived messages.** Add a `SnackbarHost` (via `Scaffold`) and route
   `engagement.message`, `state.syncMessage`, `ui.message` (account) and cast `message` through it.
   Each message is shown once and consumed; add a consume/ack path in the ViewModel instead of re-showing
   on recomposition. Persistent states (errors with Retry, "Comments are turned off", empty states)
   stay inline.
4. **Empty and error states.** Make one `StatusMessage(icon, title, body?, action?)` composable and use
   it for feed errors/empty, comments error/empty/disabled, related-empty and search empty/error.
   Keep the AuthExpired → "Open account" action.
5. **Feed skeletons.** Replace the feed's initial-load spinner with the same skeleton cards Search
   uses (extract a shared `VideoCardPlaceholder`), with a subtle shimmer that turns off when animations
   are disabled.
6. **Accessibility.** Give `VideoCard` merged semantics (`Modifier.semantics(mergeDescendants = true)`)
   with one spoken label (title, channel, duration, views, age, watched %). Make the thumbnail image
   decorative. Replace the title-as-`TextButton` toggle in the player with a proper row with
   `Role.Button` and a state description (expanded/collapsed).

Acceptance: a watched video shows a correct progress bar in feeds, search and related; tapping a
YouTube link in another app offers YouPoop and plays the right video at the right timestamp; sharing
from Chrome/YouTube works; invalid links show "Not a YouTube video link" instead of crashing; TalkBack
reads each card once.

## Phase 3: Sheets, settings and the player details area

1. **Settings screen** (new route `settings`, reached from the Account screen and the avatar menu,
   wrapped in `ScreenCard`). Sections with `ListItem` + `Switch`/radio:
   - Appearance: theme mode, wallpaper colours (from Phase 1)
   - Playback: default quality behaviour description, background audio note
   - Account sync: "Sync watch history" (moved from the player)
   - Third-party services: "Estimate dislikes (Return YouTube Dislike)" (moved), with the existing
     disclosure text as supporting text; reserve slots for SponsorBlock/DeArrow (Phase 6)
   - About: version, licence (GPL-3.0-or-later), source note
   Remove the two switches from the player details panel. Keep a small "Settings" link there if
   signed-in actions need it.
2. **Quality picker → `ModalBottomSheet`.** Rows are `ListItem` + `RadioButton`: "Auto · 1080p on
   Wi-Fi, 720p on mobile data" plus each height, the current one selected and the actual playing height
   as supporting text. No "✓" characters. Selecting applies and dismisses; position is kept (existing
   behaviour).
3. **Cast picker → `ModalBottomSheet`.** Scanning state with a progress indicator; each TV is a
   `ListItem` (TV icon, name), with its two options ("YouTube app · HD, with ads" / "Direct from phone ·
   HD, no ads · experimental") as separate tappable rows or a trailing menu. "Search again" and the
   explanatory text sit at the bottom. Keep the local-network permission flow (`ACCESS_LOCAL_NETWORK`,
   API 37) unchanged.
4. **Description sheet.** Tapping the title row opens a `ModalBottomSheet` (partially expanded first)
   showing title, channel, views/date, quality, subscribe and the full description if available (check
   `StreamInfo` description via the repository; if wiring it is non-trivial, show what is available and
   note it). Remove the inline 220dp expanding panel.
5. **Account screen restructure.** A `LargeTopAppBar` titled "Account".
   - Signed in: a profile header (avatar, name, "Connected · <method>"), then a list: Settings, Sign in
     again, Import cookies.txt, Sign out (sign out asks for confirmation in an `AlertDialog`, since it
     wipes the session and caches).
   - Signed out: one clear primary action ("Sign in with Google"), secondary actions in a list
     (device-code sign-in marked Experimental, Import cookies.txt), and the explanatory paragraphs
     collapsed under an expandable "How sign-in works" item.
   - The device-code flow (big selectable code, copy, "Open Google in Chrome", cancel) shows in a `Card`
     while pending.
   - Keep every existing behaviour, including clipboard `IS_SENSITIVE` and the Chrome-then-fallback intent.
6. **Playlist rows** in the feed become `ListItem`s with the cover as a leading 16:9 thumbnail.

Acceptance: no `AlertDialog` left for quality or cast; the player area below the video contains only
title row, like/dislike, cast status, errors and the Comments/Related pager; settings persist and take
effect; the Account screen fits one screen when signed out on a Pixel 8 with default font size.

## Phase 4: Navigation structure and top app bars

> **Decision point. Confirm with the owner before implementing.** Proposed design below.

1. **Bottom navigation** with three top-level destinations: **Home**, **Subscriptions**, **Library**.
   Library holds History, Watch Later, Liked videos and Playlists (a list of `ListItem`s or a small
   grid; opening one shows its feed). Signed out: Subscriptions and Library show a sign-in prompt
   state instead of being disabled.
   - Use `NavigationSuiteScaffold` (`androidx.compose.material3:material3-adaptive-navigation-suite`)
     so it becomes a navigation rail on wide windows (tablets, foldables, landscape non-player).
   - It must keep `FeedViewModel` as the source of truth (`select(feed)`, paging, refresh), keep a
     separate scroll position per destination, and re-tapping the selected item scrolls to top and
     then refreshes.
   - The bar hides on the player route. The mini player sits **above** the bar.
   - Predictive back: back from a non-Home tab goes to Home, then exits. Make sure `BackPreview` still
     animates correctly between NavHost entries.
2. **Top app bars.** Feed: `TopAppBar` with wordmark, search action and avatar, using
   `enterAlwaysScrollBehavior` (hides on scroll down, returns on scroll up), connected via
   `nestedScroll` and working with `PullToRefreshBox`. Opened playlist: a back-navigable `TopAppBar` with
   the playlist title (BackHandler behaviour kept). Account/Settings: `LargeTopAppBar` with
   `exitUntilCollapsedScrollBehavior`.
3. **Search.** Replace the separate search screen's header and text field with an M3 `SearchBar`
   (expanded full-screen on phones) opened from the top bar's search action:
   - keep the `search` route (or make it an overlay) so Back and predictive back still work
   - show recent searches (stored locally in DataStore; max 20; a "Clear history" entry; cleared on
     sign-out like other UI data) and the existing "Try searching" suggestion
   - results reuse the existing list, paging, skeleton and error states
   - keep or shorten the hero copy for the empty state; drop the hard-coded `38.sp`

Acceptance: all six former chip feeds are reachable within two taps; state survives rotation and
process death (`rememberSaveable`/ViewModel); no regressions in feed paging, pull-to-refresh, prefetch,
predictive back, PiP or the mini player.

## Phase 5: Material 3 Expressive and motion

1. **Bump** the Compose BOM (and `activity-compose`, `navigation-compose`, `lifecycle` if required) to
   the latest stable that ships the M3 Expressive APIs. Opt in to `ExperimentalMaterial3ExpressiveApi`
   per-file, not module-wide. Fix every deprecation. Re-run the full test and lint suite and smoke-test
   playback, PiP and predictive back. If the bump breaks something non-trivial, stop and report.
2. **`MaterialExpressiveTheme` / `MotionScheme`.** Use the expressive motion scheme for component
   motion. Leave `ScreenMotion`'s NavHost and back-preview timings alone unless they clearly improve.
3. **Like/dislike → connected `ButtonGroup`** (toggle buttons, icon + count, selected state filled,
   haptic `HapticFeedbackType.Confirm`/`LongPress` on change). The existing semantics labels
   ("Like"/"Remove like") and the signed-out disabled state with "Sign in to rate" stay.
4. **Subscribe** becomes a filled button when not subscribed and a tonal button when subscribed, with a
   short shape/colour morph and a haptic.
5. **Loading indicators.** Replace `CircularProgressIndicator` for indeterminate loading with
   `LoadingIndicator`/`ContainedLoadingIndicator` (player buffering overlay, mini player, load-more,
   cast scanning). Use the expressive indicator for pull-to-refresh
   (`PullToRefreshDefaults.LoadingIndicator`).
6. **Optimistic rating and subscribe.** Update the UI immediately and roll back with a snackbar
   ("Couldn't update rating. Retry") on failure. Unsubscribe shows "Unsubscribed · Undo".
   Keep the `EngagementViewModel` as the single source of truth.

Acceptance: no visual or behavioural regressions; animations respect "Remove animations"; haptics
fire once per state change.

## Phase 6: Player UX

1. **Draggable player ↔ mini player.** The player becomes a surface you can drag:
   - drag down from the video (or tap a collapse affordance) to shrink into the mini player docked above
     the navigation bar
   - drag up or tap the mini player to expand; swipe the mini player sideways to dismiss (= close)
   - playback, `PlayerView` surface and PiP source-rect hints must survive the transition with no
     black frame or re-buffer; prefer moving one `PlayerView`/surface over two competing ones
   - Back from the expanded player collapses it (with predictive-back progress if feasible); fullscreen,
     landscape and PiP auto-enter keep working
   - if this needs a different structure than the `player` NavHost route, propose it before rewriting
2. **Shared-element transition** from a `VideoCard` thumbnail to the player video area
   (`SharedTransitionLayout`), when opening from a card.
3. **Gestures on the video** (Compose overlay above `PlayerView`; must not break the controller's tap
   to show/hide):
   - double-tap left/right third: seek −10s/+10s with a ripple and an accumulated "+20s" label;
     respect TalkBack (offer the same actions as custom accessibility actions)
   - long-press: 2× speed while held, with a "2×" pill
   - swipe up (portrait) → fullscreen, swipe down (fullscreen) → exit, swipe down (portrait) → collapse
   - pinch in fullscreen: toggle fit/zoom-to-fill (`AspectRatioFrameLayout` resize mode),
     cutout-aware
4. **Seek bar chapters and preview thumbnails.** Use NewPipe `StreamInfo` stream segments
   (chapters) and preview frames (storyboards). Show chapter ticks and the current chapter title, and a
   thumbnail preview while scrubbing. If Media3's `PlayerView` time bar can't do this cleanly, build a
   Compose seek bar for these features and keep the controller's other buttons. Parse storyboard
   frame geometry in a tested pure function. Storyboard images load through the anonymous image
   pipeline only.
5. **Ambient mode** (dark theme, portrait, API 31+): a soft blurred glow of the current video colours
   behind the player and title area. Use a downscaled thumbnail with `RenderEffect` blur, with low
   alpha. Off in light mode, in PiP and with power saver on, and add a Settings toggle (default on).
   Must not run per-frame video sampling.
6. **Content-based colour on the player screen.** Derive a scheme from the current thumbnail
   (material-color-utilities `QuantizerCelebi` + `SchemeContent`/`SchemeTonalSpot`, or `androidx.palette`
   if lighter) and apply it via a nested `MaterialTheme` to the area below the video (buttons, tabs,
   chips). Fall back to the app scheme until computed. Do the colour work off the main thread and cache
   it per video. Settings toggle "Colour player from video" (default on).

Acceptance: drag, gestures and seek preview work in portrait and fullscreen at 60fps on a Pixel 8
(no jank in the Layout Inspector or `gfxinfo`); PiP, background audio, the notification and casting
are unaffected.

## Phase 7: Privacy-client features and the "quiet" ethos

1. **SponsorBlock** (Settings → Third-party services, default off):
   - fetch segments via `https://sponsor.ajay.app/api/skipSegments/<sha256-prefix-4>` and keep only
     segments for the current ID; categories sponsor, selfpromo, interaction, intro, outro, preview,
     filler, each set to Skip / Show only / Off (sensible defaults: sponsor = Skip, others = Show only)
   - mark segments on the seek bar (Phase 6) with category colours from the theme
   - auto-skip with a "Skipped sponsor · Undo" snackbar; manual "Skip" pill for show-only segments
   - never report back or vote. Read-only
   - the disclosure says which data is sent (a 4-char hash prefix of the video ID, plus your IP to that service)
2. **DeArrow** (default off): fetch crowd-sourced titles/thumbnails via the hash-prefix branding
   endpoint and apply them to `VideoCard` and the player title. Keep the original title available
   (long-press or the description sheet) and add the same disclosure pattern.
3. **Calm options** (Settings → Feed, all default off unless stated):
   - hide view counts; hide like counts (cards and player)
   - autoplay next related video when one ends (default **off**; respects playlists)
   - Subscriptions: "You're all caught up" marker after videos already seen (based on local progress)
   - grayscale thumbnails until tapped (an optional focus mode)
4. **Long-press menu on `VideoCard`** (`ModalBottomSheet`): Share (system share sheet with the
   canonical URL), Open channel (if a channel page exists; otherwise omit), Copy link, and placeholders
   for Watch Later / Not interested **only if** the account action exists (Watch Later mutations are
   deferred per the README, so don't add a broken action).
5. **App shortcuts:** static Search; dynamic "Continue watching <title>" for the last unfinished video
   (from Room). Shortcuts must not include signed stream URLs.

Acceptance: with all third-party toggles off, a network capture shows no requests to
`sponsor.ajay.app`, the DeArrow host or `returnyoutubedislikeapi.com`; with them on, requests contain
only the disclosed data.

---

## Out of scope
- Shorts UI, upload, live chat, notifications/inbox, downloads, Glance widgets, Wear/TV form factors.
- Watch Later add/remove (deferred in README).
- Changing extraction, auth, cookie storage or the authenticated client.

## Reporting (after each phase)
Reply with:
1. What changed, per requirement, with `file:line` references.
2. Commands run and their results (paste failures verbatim). Say what was **not** verified,
   e.g. on-device checks without a device.
3. Screenshots (light and dark) of changed screens if a device or emulator was available.
4. Any requirement you deviated from or deferred, and why.
5. Open questions needing an owner decision.

Don't commit or push unless asked. Don't edit unrelated files.
