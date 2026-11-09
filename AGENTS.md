# AGENTS.md

Guidance for AI coding agents (and people) working on Quietube, an Android
YouTube client in Kotlin and Jetpack Compose. Package: `dev.quietube`; single
module: `:app`.

## Quick reference

```sh
./gradlew assembleDebug                         # build
./gradlew testDebugUnitTest                     # JVM unit tests (offline)
./gradlew testDebugUnitTest --tests 'dev.quietube.links.*'   # one package/class
./gradlew lintDebug                             # lint: no new warnings or errors
./gradlew assembleRelease                       # R8-shrunk build for daily use
```

Requirements: JDK 17, Android SDK Platform 37, Build Tools 36. `local.properties`
(with `sdk.dir`) is machine-specific and git-ignored.

## Workflow

1. **Understand first.** Read the relevant code and the matching section of
   `docs/development-log.md` before changing behaviour. The log records why
   things are the way they are, including YouTube quirks that were already
   investigated.
2. **Plan in small, shippable steps.** For larger work, split it into phases
   that each build, pass tests and lint, and work on their own. Stop after each
   phase and report, rather than running ahead.
3. **Implement** following the style and constraints below.
4. **Verify** every change:
   - `./gradlew assembleDebug testDebugUnitTest lintDebug` passes.
   - Add JVM unit tests for new pure logic (parsing, URL handling, maths,
     formatting, state reducers). Keep that logic out of composables so it can
     be tested without Compose.
   - If a device or emulator is available, install the APK and check the
     acceptance criteria by hand. If not, say clearly that on-device checks were
     not run.
5. **Document** behaviour and privacy-relevant changes in
   `docs/development-log.md` (append a new section; keep its tone). Update
   `README.md` only when user-facing features or build steps change.
6. **Report honestly.** List what changed, the test and lint results, what was
   verified on a device, and what remains unchecked. Claim only what you
   verified.

## Code style

- Write compact Kotlin that matches the surrounding code: long lines, few blank
  lines, small composables, and `internal` for helpers inside a package.
- Use few comments: one line each, explaining *why* (constraints, platform or
  YouTube quirks), never *what*. Keep existing comments that still hold.
- Keep state in the existing ViewModels (`SearchViewModel`, `FeedViewModel`,
  `AccountViewModel`, `PlaybackViewModel`, `CommentsViewModel`,
  `EngagementViewModel`, `CastViewModel`). Composables stay stateless apart from
  UI-only `remember`/`rememberSaveable` state.
- Screens take lambdas, not ViewModels.
- Use Hilt for DI, DataStore for preferences and Room for local progress. Don't
  add another DI, persistence or networking framework.
- Pin dependency versions. Ask before adding a dependency.

## Hard constraints (privacy and security)

These are product requirements, not preferences. Do not weaken them.

- **No** Google Play Services, Firebase, analytics, crash reporting or HTTP
  logging dependencies.
- **Never** log, persist in plain text, or send anywhere new: account cookies,
  authorization headers, OAuth tokens or signed stream URLs. Signed stream URLs
  stay in memory only (never Room, DataStore, logs or intents).
- Don't widen the authenticated OkHttp client's destination allow-list
  (`www.youtube.com` homepage and `/youtubei/v1/` only, no redirects).
- New third-party network integrations are **off by default**, disclose what
  they send in the UI, and follow the `DislikeEstimates` pattern in
  `account/actions/SyncPreferences.kt`: own OkHttp client, `CookieJar.NO_COOKIES`,
  no redirects, a call timeout, a capped body, input validation and a small cache.
- Sign-out must keep removing every account-derived piece of data. If you add
  account-scoped storage, add it to the sign-out cleanup and test it.
- Keep backups, device transfer and cleartext traffic disabled. Local-network
  HTTP for casting goes only through the restricted socket in `cast/`.

## Platform and UX constraints

- minSdk 26, targetSdk 37. Gate newer APIs with `Build.VERSION.SDK_INT` and give
  a sensible fallback.
- Navigation goes through the predictive-back motion in `ui/ScreenMotion.kt`
  (`BackPreview`, `ScreenCard`).
- Keep PiP, fullscreen, `MediaSessionService` playback, casting, comments and
  feed paging, and prefetching working as they do now.
- Respect "Remove animations" and font scaling. Touch targets must be at least
  48dp.

## Tests and fixtures

- Unit tests live in `app/src/test/java/dev/quietube/**`, with fixtures in
  `app/src/test/resources/`. Tests must not contact YouTube or any other live
  service. Use fakes, MockWebServer and virtual coroutine time.
- Fixtures captured from real responses **must be sanitized**: strip visitor
  data, tracking params, feedback and continuation tokens, account names,
  channel IDs and any locale or location hints. Prefer synthetic fixtures with
  obviously fake IDs (`FAKE_ACCESS`, `UCabcdefghijklmnopqrstuv`).
- Device tests (`app/src/androidTest`) can uninstall the app. Never run them
  against an installation whose data matters.

## Repository hygiene

- Never commit secrets, cookies, `cookies.txt` exports, keystores, device
  screenshots of a signed-in account, logs, absolute local paths or personal
  emails. `.tools/`, `build/` and `local.properties` are git-ignored; keep
  scratch output there.
- Before committing, scan the diff for personal data. For example:
  `git diff --cached | grep -nE '/Users/|/home/|@[a-z0-9-]+\.(com|net|org)'`.
- Use small commits with imperative subjects (for example "Fix History
  continuation paging"). Bump `versionCode`/`versionName` in
  `app/build.gradle.kts` for user-visible releases.
- YouTube changes often. When a parser breaks, add a sanitized fixture that
  reproduces it before you fix it.

## Releasing

Releases are GitHub releases on `main`, each with the release APK attached.
Publishing is outward-facing: only cut a release when the owner asks for one.
`gh release create` creates the tag, so don't tag by hand.

1. **Bump the version** with the changes, usually in their PR: increase
   `versionCode` by 1 and set `versionName` (for example `0.5.8`) in
   `app/build.gradle.kts`. The tag is `v` + `versionName`.
2. **Merge to `main`**, then pick the exact commit to release:
   `git fetch origin && git rev-parse origin/main`.
3. **Build from that commit in a clean checkout.** If the working tree is dirty
   or on another branch, use a temporary worktree under `.tools/` rather than
   stashing or switching branches:
   ```sh
   SHA=$(git rev-parse origin/main)
   git worktree add --detach .tools/release "$SHA" && cp local.properties .tools/release/
   (cd .tools/release && ./gradlew testDebugUnitTest assembleRelease)
   cp .tools/release/app/build/outputs/apk/release/app-release.apk .tools/quietube-0.5.8.apk
   ```
   Check that `versionName` in that checkout matches the tag you're about to
   create.
4. **Write the notes** in a file under `.tools/`: user-visible changes since the
   last release, the minimum Android version (8.0, API 26), the APK's SHA-256
   (`shasum -a 256`), and a note that it is signed with a debug key.
5. **Publish**, targeting the exact SHA, not the branch name:
   ```sh
   gh release create v0.5.8 .tools/quietube-0.5.8.apk --target "$SHA" \
     --title "Quietube 0.5.8" --notes-file .tools/release-notes-0.5.8.md
   ```
6. **Verify and clean up:** run `gh release view v0.5.8` (asset present, not a
   draft), then `git fetch --tags`, then `git worktree remove .tools/release`.
   Report the release URL.

Rules:
- Never move, delete or re-use a published tag. To fix a bad release, bump the
  version and publish a new one. Re-upload an asset (`gh release upload
  --clobber`) only if it was built from the tagged commit.
- Release builds are signed with the local debug key, and Android only installs
  updates signed with the same key. Don't change signing without the owner.
  Never commit a keystore.
- Only release from `main`. If an old version needs a fix while `main` has
  unreleased work, ask before creating a `release/X.Y` branch from its tag.
