# UI refresh — Phase 1 report

## Changes

1. Full red-seeded light/dark colour schemes: `app/src/main/java/dev/quietube/ui/Theme.kt:32` and `:71`. Generated offline with Material Color Utilities 0.3.0; all 36 roles in the pinned Material 3 version are explicit. Dark surfaces and action reds are adjusted for contrast. No runtime dependency added.
2. System/Light/Dark and API-31-gated opt-in wallpaper colours: `ui/AccountScreen.kt:37`, `ui/AppearancePreferences.kt:14`, `ui/Theme.kt:136` (paths relative to `app/src/main/java/dev/quietube/`). Existing AccountViewModel owns preference state; screens take callbacks.
3. Separate `appearance_preferences` DataStore: `ui/AppearancePreferences.kt:12`. Account sign-out never references or clears it. README's DataStore description updated.
4. XML light/night themes and API-31 native splash: `app/src/main/res/values/styles.xml:1`, `values-night/styles.xml:1`, `values-v31/styles.xml:1`, `values-night-v31/styles.xml:1`. API-27 navigation-bar attributes have separate resources. `MainActivity.kt:63` gates the first frame on preference loading and updates system bars. Application night mode persists the splash choice on API 31+. `AndroidManifest.xml` handles `uiMode` in place to retain navigation and playback.
5. Explicit Roboto typography and shapes: `ui/Theme.kt:113`. Search hero, thumbnail/skeleton shapes, account pill, placeholder tint and duration badges use theme tokens. Search welcome content scrolls at larger font sizes. Video remains black with white overlay controls; portrait system-bar padding follows the theme.
6. Themed launcher icon: `app/src/main/res/drawable/ic_launcher_monochrome.xml:1`, referenced from `mipmap-anydpi/ic_launcher.xml`. Vector traced from the alpha cutout in `art/logo.svg`, simplified and fitted inside the adaptive-icon safe zone.
7. `ui/AppearanceTest.kt` under `app/src/test/java/dev/quietube/`: default/fallback mode parsing, every light/dark selection combination, dynamic-colour API/opt-in gating, and contrast checks for text/container pairs (4.5:1) and icons (3:1).

## Verification

Commands use the workspace JDK 17 (`JAVA_HOME=.tools/jdk-17.0.21+9/Contents/Home`).

- `./gradlew assembleDebug`: passed.
- `./gradlew assembleDebug testDebugUnitTest lintDebug`: passed; 104 JVM tests, zero failures. Lint: zero errors, 41 existing warnings, zero new warnings compared with the pre-change report.
- `git diff --check`: passed.
- Token audit: no colour literals or `RoundedCornerShape(...)` outside Theme.kt. Predictive-back's existing black scrim stays in ScreenMotion; its dynamic corner calculation calls a Theme helper without changing the geometry.
- Debug APK installed over the existing app on Pixel 8, preserving the account.
- Pixel: Account light/dark mode controls and system-bar appearance checked; wallpaper toggle checked; back navigation retains screen state. Search and player/mini-player inspected in both modes. Playback remained PLAYING and advanced from 172,354 ms to 176,174 ms across navigation and the dark-to-light switch. PiP entered the pinned task mode afterward. Playback paused after smoke testing.
- Appearance survived APK replacement/process restart. Account sign-out survival was checked by code inspection only; the owner's session was not erased.

## Screenshots

Local device captures are in `.tools/ui-refresh-phase1/`:

- `account-light.png`, `account-dark.png`, `account-wallpaper.png`
- `home-dark.png`, `home-light.png`, `search-dark.png`, `search-light-mini.png`
- `player-light.png`, `player-dark.png`

## Deviations and remaining checks

- Before API 31, the starting-window preview is disabled until the themed first frame; no new splash library is introduced. Splash backgrounds use the neutral brand surface, not wallpaper-derived colour.
- The lint run exposed an existing minSdk bug in `cast/StreamService.kt:35`. The foreground-service call now uses `ServiceCompat.startForeground`, preserving behaviour on API 29+ and adding the API 26–28 fallback. No other cast behaviour changed.
- Full sign-out, API 26–30 launch behaviour, themed-icon rendering with Pixel launcher's themed-icons option enabled, large-font device acceptance, Remove animations, casting, and predictive-back gesture acceptance remain unverified on-device. No claim of a complete regression pass.
- No owner decision needed for Phase 1. Phase 2 has not started, as required by the prompt. Nothing committed or pushed.

## Intermediate failures (resolved)

The first build ran before the monochrome drawable had been written:

```text
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:lintDebug'.
> Lint found errors in the project; aborting build.
  
  Fix the issues identified by lint, or create a baseline to see only new errors.
  To create a baseline, run `gradlew updateLintBaseline` after adding the following to the module's build.gradle file:
  ```
  android {
      lint {
          baseline = file("lint-baseline.xml")
      }
  }
  ```
  For more details, see https://developer.android.com/studio/write/lint#snapshot
  
  Lint found 3 errors, 43 warnings. First failure:
  
  app/src/main/java/dev/quietube/cast/StreamService.kt:35: Error: Call requires API level 29 (current min is 26): android.app.Service#startForeground [NewApi]
          startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
          ~~~~~~~~~~~~~~~
  
  The full lint text report is located at:
    app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 30s
60 actionable tasks: 32 executed, 28 up-to-date

```

The missing-icon build log was replaced by a successful rerun. Its resource error was:

```text
error: resource drawable/ic_launcher_monochrome (aka dev.quietube:drawable/ic_launcher_monochrome) not found.
```

New lint findings were fixed rather than suppressed: API-27 navigation-bar XML attributes moved to qualified resources; the vector path was simplified; the window background uses the KTX drawable extension.
