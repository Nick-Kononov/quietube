# Quietube

A private, GPL-3.0-or-later Android YouTube client, built in Kotlin and Compose.

## Implemented phases

Phase 1: single activity, Navigation Compose, Material 3 dark UI, Hilt DI,
source-neutral `VideoRepository`, anonymous search, thumbnails, metadata,
continuation paging, suggestions, retry/empty states and query restoration.
Search was verified by the owner on a Pixel 8.

Phase 2: tap search or related results to play. Media3 1.11.1 playback lives
inside `MediaSessionService`, with notification, lock-screen and Bluetooth
controls, audio focus, headphone-disconnection handling and a playback wake
lock. Split video/audio tracks are merged with `MergingMediaSource`; muxed
streams and HLS/DASH manifests are fallbacks. Live streams prefer HLS.
Quality defaults to 1080p on Wi-Fi and 720p on other connections when selecting
a video; the quality dialog permits an override and switching preserves the
position. Available resolution depends on the video. Auto is a network-based
resolution cap; split direct streams are fixed selections, not an adaptive
bitrate ladder. Choose Auto again after changing networks to reapply its cap.

PiP auto-enters from the player on the Home gesture on Android 12+, with a
fallback on older devices and an explicit PiP button. Returning to search
keeps playback running and shows a shortcut back to the player. Room saves
local progress every five seconds, on pause, seek and track changes, and on
service shutdown. Completed videos restart. A new selection resolves fresh
anonymous stream URLs; a rejected URL is re-extracted once before showing an
error. Signed stream URLs remain in memory, never in the database or logs.

Phase 3 includes an experimental phone-only device-code OAuth path (see the
0.3.2 prototype below), Google sign-in in an isolated login WebView with a desktop
Chrome user agent, and Netscape cookies.txt import through Android's file picker. A candidate cookie
session must produce a signed-in YouTube homepage before it is stored. Cookies
are encrypted with an Android Keystore AES-256-GCM key in `noBackupFilesDir`.
WebView cookies/storage/cache are cleared after capture or cancellation; the
login activity is not exported, disables debugging/file access and prevents
screenshots. The app never logs session cookies or authorization headers.

Home, subscriptions, history, Watch Later, liked videos and playlist tabs use a
custom InnerTube `AccountSource`. Library playlists open through the same
repository. Parsers support legacy renderers and lockup view models, skip
malformed entries and support infinite continuation paging plus a Load more
button. Signed-out home uses a local feed built from anonymous search results.
Read-only progress overlays from account pages can seed local playback resume.

The authenticated OkHttp client is separate from extractor/playback networking;
it disables redirects and can send credentials only to the exact HTTPS
`www.youtube.com` homepage or `/youtubei/v1/` paths. WEB version/context and
visitor data are scraped from ytcfg and refreshed every six hours. API auth uses
current yt-dlp SAPISIDHASH/1PHASH/3PHASH logic, including optional user-session
suffixes. Account requests are serialized at least one second apart; initial
feed pages are cached for 30 seconds to limit repeated refreshes. Expired
sessions produce a recovery message; import fresh cookies or sign in again.

Sign out removes the encrypted session and key, account feed/progress caches,
local feed and Room resume rows, image caches and WebView session data. Active
playback is cleared on account sign-out. DataStore retains only UI preferences,
which are cleared when signed out. No account secrets are stored in DataStore.

Phase 4 reporting, ratings and subscriptions are implemented in 0.4.0; see the
acceptance checklist below. Watch Later mutations remain deferred.
No Google Play Services, Firebase, analytics or HTTP logging dependencies.
Application backup, device transfer and cleartext traffic are disabled.

## Build

Use JDK 17, Android SDK Platform 37.0 and Build Tools 36.0.0. The Gradle 9.4.1
wrapper is included and its download is checksum pinned. AGP 9.2.1 uses built-in
Kotlin 2.2.10 with the matching Compose compiler. minSdk 26, targetSdk 37.
Core library desugaring includes NIO as used by current NewPipe.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This workspace also has ignored local tools under `.tools/`. To use them:

```sh
export JAVA_HOME="$(find "$PWD/.tools" -type d -path '*/Contents/Home' | head -1)"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew assembleDebug
```

`local.properties` points at that SDK and is intentionally ignored. On another
machine use Android Studio or set `sdk.dir` to your own SDK path.

## Device acceptance check

1. Install the APK and open **Quietube**. Search for a normal 1080p video.
2. Tap a result. Check video and audio, and that Auto reports 1080p on Wi-Fi.
3. Select 720p, then 1080p. Confirm the current position is preserved.
4. Press Home from the player. Check PiP, its pause control and return-to-app.
5. Lock the phone while playing. Check continued audio and lock-screen controls.
6. Return to search. Confirm the player shortcut opens the same video.
7. Select a related video. Reopen the previous video and confirm local resume.
8. Test a live stream if available. It should use HLS.
9. Disable connectivity and select a video. Confirm the useful error and retry.

YouTube may change stream access independently of build success. If the server
rejects streams with 403 after one URL refresh, the app reports that proof-of-
origin verification may be needed. The NewPipe WebView PoToken provider is
intentionally deferred until a real playback failure demonstrates it is needed.

## Tests

Offline tests use fake repositories, virtual coroutine time and a local
MockWebServer. They check HTTP body/header handling, absence of session headers,
rate-limit/error mapping, stale search cancellation, pagination retry and
query restoration, distinguishing empty searches from parse failures, stream
selection (split vs muxed, missing audio, quality caps, manifests) and local
resume behavior. No automated test contacts live YouTube.

Account tests cover current auth hashes against independent SHA-1 fixtures,
Netscape import (expiry, domain/path isolation, HttpOnly, injection rejection),
legacy/modern renderers, malformed items, continuations, history progress, ytcfg
and credential destination restrictions. Captured public fixtures have tracking
fields removed; synthetic account fixtures are explicitly identified.

Six Android device tests use an isolated directory and Keystore alias with fake
credentials to verify encryption, restoration, sign-out deletion and corrupted
ciphertext handling. They make no YouTube requests. Run connected tests on a
separate test device: the Gradle runner may uninstall its target APK afterward.
Do not run them against an installation whose user data you want to preserve.

## Source and version verification

NewPipeExtractor **v0.26.5** was verified with the GitHub latest-release API,
and its JitPack POM was retrieved successfully. Dependencies are pinned.
Checked sources (2026-11-07):

- [NewPipeExtractor latest release](https://github.com/TeamNewPipe/NewPipeExtractor/releases/latest)
- [Extractor v0.26.5 search API](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/search/SearchInfo.java)
- [Extractor downloader contract](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/downloader/Downloader.java)
- [Extractor artifact POM](https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5.pom)
- [Android SDK repository v3](https://dl.google.com/android/repository/repository2-3.xml)
- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Google Maven index](https://maven.google.com/web/index.html)

The older SDK repository v1 omits API 37; the current v3 repository contains
stable `platforms;android-37.0` as well as minor platform releases.

## Next phases (require go-ahead)

4. History/progress reporting and account actions.
5. SponsorBlock, downloads and settings.

NewPipeExtractor is GPL-3.0-or-later. This project is licensed compatibly; see
[LICENSE](LICENSE). Retain upstream notices when redistributing source/binaries.

## Build verification

`./gradlew assembleDebug testDebugUnitTest lintDebug --max-workers=4` passed.
All 77 offline unit tests passed on the current build. The 6 isolated Android storage tests passed before the Phase 4 changes. Lint has no errors; warnings include
newer-dependency notices and conservative checks for PiP and the exported
MediaSessionService. Custom service commands are restricted to this app. PiP
parameters are set dynamically, including auto-entry and the player bounds.
APK signature verification and 16 KB ZIP alignment checks passed.
Search acceptance passed on the owner’s Pixel 8. Phase 2 device checks are
recorded below as they are completed.

### Phase 2 references

- [Media3 1.11.1 release notes](https://developer.android.com/jetpack/androidx/releases/media3)
- [Service playback lifecycle](https://developer.android.com/media/media3/session/background-playback)
- [MediaSession 1.11.1 source](https://github.com/androidx/media/blob/1.11.1/libraries/session/src/main/java/androidx/media3/session/MediaSession.java)
- [PiP lifecycle](https://developer.android.com/develop/ui/views/picture-in-picture)
- [Extractor stream API](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/stream/StreamInfo.java)

### Phase 2 device verification (Pixel 8, Android API 37)

- Debug APK 0.2.0 installed successfully over ADB.
- Big Buck Bunny (`aqz-KE-bpKQ`) played with rendered video at the default
  1080p selection; Android reported the session as PLAYING.
- Manual 720p and 1080p selections worked without restarting from zero.
- Local progress survived an APK replacement and fresh stream extraction.
- Home entered PiP (Android task mode `pinned`, PiP flag true) while playing.
- With the display dozing, the media session continued advancing and the
  service remained foreground with a media notification.
- The test video was paused afterward. Live HLS, Bluetooth hardware and
  cellular-network playback still need owner testing.
- No 403 or throttling failure occurred in this test; no PoToken provider was
  needed or added.

### Original audio fix (0.2.1)

Audio selection preserves NewPipe's track type and language metadata. An explicit
original track wins before codec/bitrate preferences, regardless of its language.
Unknown legacy tracks remain playable; known dubs and descriptive tracks are
fallbacks. Split original audio also takes precedence over potentially localized
muxed audio. Manifest playback prefers the detected original language and main
role instead of a hardcoded English preference. Regression tests cover Russian
and English originals, higher-bitrate dubs, legacy metadata and quality fallback.

## Phase 3 account acceptance

1. Open **Sign in / Account** and choose **Sign in with Google**. Complete the
   Google flow yourself. The app returns after verifying the YouTube session.
2. If Google blocks the embedded browser, close it and use **Import cookies.txt**.
   Export a Netscape file from a desktop browser signed in to youtube.com and
   transfer it to the phone. The file itself stays in your chosen storage;
   remove that export yourself when no longer needed. Never paste it into chat.
3. Return Home and compare recommendations with your youtube.com homepage.
   Try subscriptions, history, Watch Later, liked videos and playlists.
4. Scroll to trigger continuation loading. Select a playlist and play a video;
   stream URLs should still be resolved anonymously.
5. Close/reopen the app and verify the session restores.
6. Use **Sign out and clear session**. Confirm tabs return to signed-out home
   and account pages require sign-in. This removes the encrypted file and its key.
7. Sign in again if desired. Watching does not report progress back to YouTube
   until Phase 4, and like/subscribe/playlist mutations are not implemented yet.

Google may reject embedded WebViews regardless of user agent; the cookies.txt
path is always available. This is a full browser session, not an OAuth token.

Phase 3 sources checked on 2026-11-07:

- [yt-dlp current YouTube auth and WEB context](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py)
- [yt-dlp current lockup and continuation parsing](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_tab.py)
- [ytmusicapi browser authentication](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/auth/browser.py)

Build 0.3.0 includes the implementation and automated checks. Owner-driven real
account sign-in/feed acceptance is recorded when completed; no private session
or authenticated response has been copied into this repository.

Phase 3 owner test: Google rejected the embedded WebView as an insecure browser
on this Pixel. The desktop user-agent path was attempted; cookies.txt import was
the recovery route before the device-code prototype. Real-account feed acceptance remains pending.
Use the current yt-dlp wiki's incognito export procedure to avoid cookie rotation:
https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies

## Phone-only session maintenance (0.3.1)

The Android app works independently. No Mac companion, browser synchronization
service, public server, pairing receiver or ADB connection is needed during use.
The cancelled companion implementation was removed from source and the APK.

YouTube response Set-Cookie headers are now merged into the encrypted session.
Updates apply only to root-path youtube.com/www.youtube.com cookies. Foreign
cookies, malformed values and unrelated paths are rejected. Expiry metadata is
preserved across restarts, expired cookies are omitted, and server-deleted login
cookies invalidate and remove the stored session. Ordinary rotation preserves
the account revision so paging keeps working. Responses from a prior revision
cannot resurrect a session after sign-out. Existing encrypted header-only session
records remain readable and migrate on their next write. Login/import also save
any cookie updates returned during verification.

This maintains updates supplied by YouTube; it cannot recreate a revoked session
or bypass a Google requirement to sign in again. No artificial keep-alive requests
or permanent-login guarantees are added. The initial WebView login remains blocked
by Google on this Pixel, so real-account feed acceptance still requires import.

An initial/recovery import can be done entirely on the phone: Firefox for Android
supports the cookies.txt extension by Lennon Hill:
https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/
Sign in to YouTube in Firefox, export the current site's cookies in Netscape
format, and select that download in Quietube's Import cookies.txt picker. A
computer is optional for creating the export. Keep the export private and delete
it after import. The browser/extension is not needed for ordinary app playback.

Verification: assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and
lintDebug passed. The five isolated Keystore tests were run directly with adb
instrumentation, preserving the installed app and user data (no Gradle connected
runner cleanup). The APK has no companion receiver or companion code and was
installed as version 0.3.1 on the Pixel.

## Phone-only device-code prototype (0.3.2-prototype)

Open **Account → Try device-code sign-in**, copy the displayed activation code,
and choose **Open Google in Chrome**. Complete Google's approval yourself, then
return to Quietube. Google may identify the client as **YouTube on TV**, rather
than Quietube. This prototype uses the published TV client's configuration,
discovered from YouTube's current TV page and script; it does not use a private
Quietube Google Cloud client. No computer, cookie-export extension, Play Services
or embedded Google login is needed for this path.

The app polls at Google's requested interval, handles pending approval, slow-down,
denial and expiry, and allows cancellation. Approval alone does not replace an
existing session: the candidate must produce a nonempty Home feed, successfully
refresh its token, and load Home again with the refreshed token. These checks
require an account that has Home recommendations. After success, open Home and
compare the recommendations yourself. TV recommendations can differ from WEB.

Access and refresh tokens are stored in the same Android Keystore encrypted,
backup-excluded storage as cookie sessions. Access tokens refresh before expiry
when account requests need them. TV client version/visitor context is refreshed
periodically. Refresh tokens may still expire or be revoked, requiring approval
again. Sign out removes local tokens and their encryption key; it does not claim
to revoke Google's server-side authorization. Revoke that separately in Google
account settings if desired.

The TV account client uses an isolated cookie-free HTTP client, restricts account
destinations, disables redirects and never logs credentials. Anonymous extraction
and playback receive no OAuth tokens. TV Home maps to `default`, library to
`FEmy_youtube`; existing account tabs continue using the repository interface.
Parsing supports TV video/playlist tiles and continuation tokens in addition to
the existing WEB renderers. Watch reporting, likes, subscriptions and comment
posting are not part of this prototype.

This is a private YouTube client flow, not a guarantee supplied by the public
YouTube Data API. Google may change or reject it. Cookie import remains available.

Verification: debug assembly, all 52 offline unit tests and lint passed. All six
isolated Keystore device tests passed on the Pixel, including encrypted OAuth
restoration, refresh without a session revision change, and stale-token rejection
after sign-out. Synthetic TV renderer fixtures and OAuth parsing tests require no
live account. Manual public endpoint probes confirmed device-code issuance and
the pending-approval response. APK 0.3.2-prototype is installed on the Pixel.
The owner confirmed successful device-code sign-in and personalized Home on
2026-11-07. The successful sign-in path requires Home to load both before and
after an immediate token refresh. Long-term refresh across expiry/restarts,
subscriptions, history/library tabs, continuation paging and sign-out still need
owner acceptance checks. Phase 4 has not started.

Sources inspected for this prototype:

- [SmartTube device-code/token endpoints](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/auth/V2/AuthApi.java)
- [SmartTube device-code and refresh request parameters](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/auth/V2/AuthApiHelper.java)
- [SmartTube service source](https://github.com/yuliskov/MediaServiceCore)
- [SmarterTube phone client reference](https://github.com/CodeSculptor/SmarterTube)

## Reporting, ratings and subscriptions (0.4.0)

The owner confirmed all Phase 3 account checks worked before authorizing this
phase. Phone-only device-code login remains the primary tested account method.

The player now reads the current account rating, like count, creator channel ID
and subscription state from authenticated player/next metadata. Like and Dislike
are mutually exclusive; tapping the active rating removes it. Subscribe and
Unsubscribe use the creator's validated channel ID. Unknown subscription state
shows an unavailable control instead of guessing. Actions are serialized with
account requests and update the UI after an accepted response; Refresh retrieves
current state. Signed-out playback continues, with account mutations disabled.

Watch-history sync defaults on and can be disabled in the player. A separate
account player request obtains signed playback/watchtime tracking URLs; stream
resolution and media requests remain anonymous. One random 16-character playback
nonce is shared across the session's playback and watchtime pings. Tracking URLs
are restricted to HTTPS YouTube stats paths, use no cookie/bearer headers and are
never logged or persisted. Reporting preserves the returned tracking parameters,
adds real playback position and watched start/end intervals, and runs every 30
seconds while playing, on pause/seek/video changes and on ordinary service stop.
It continues during background playback. Seeks, buffering and pauses do not count
as watched ranges, and no fully-watched shortcut is used. Sign-out/revision guards
prevent queued samples from being sent under a different session. Failed reports
show a player status and do not stop playback; failed segments are not queued to
disk. Forced process termination can lose the most recent unsent interval.

YouTube supplies the like count when available; hidden/missing counts show a dash.
**Estimate dislikes** is off by default. Enabling it fetches video-ID-only data
from Return YouTube Dislike, labels dislike totals with `≈`, and caches responses
in memory for 30 minutes. These are third-party estimates, not YouTube's private
exact totals; unavailable estimates leave a dash. No Google credentials or votes
are sent to that service. The sync and estimate toggles persist as ordinary
preferences. New videos use the current preferences.

Owner acceptance for this update:

1. Play a video, pause after at least 30 seconds, and check youtube.com History
   and resume progress. Seek ahead and confirm skipped time is not reported as
   watched. Check playback with the screen off and with sync disabled.
2. Like a video and verify the rating on YouTube; tap again to remove it. Test
   switching between Like and Dislike, then restore the desired rating.
3. Subscribe/unsubscribe on a creator and confirm the change on YouTube. Refresh
   should reflect the latest subscription and rating state.
4. Enable estimated dislikes if desired and check the approximate label. Disable
   them to stop third-party requests. Test offline failures and sign-out.

Watch Later mutations, comments and Phase 5 extras are not implemented in this
update. Real account history/progress and mutation acceptance remains pending;
a successful HTTP response alone is not proof that YouTube retained progress.

Current source references:

Verification for 0.4.0: `assembleDebug testDebugUnitTest lintDebug` passed with 66
unit tests and no lint errors. New offline coverage includes synthetic TV account
metadata, unknown states, unrelated channels, rejected action responses, tracking
destination restrictions, signed-parameter preservation and watched intervals
across seeks, pauses, buffering, account revisions and disabled sync. APK signing
and 16 KB ZIP alignment checks passed. The update was installed on the Pixel
without clearing its account data. No ratings or subscriptions were changed by
automated tests.

- [SmartTube action endpoints](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/actions/ActionsApi.java)
- [SmartTube action request bodies](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/actions/ActionsApiHelper.java)
- [SmartTube tracking parameters](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/track/TrackingApi.java)
- [yt-dlp current mark-watched code](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py)
- [Return YouTube Dislike data sources and estimation](https://github.com/Anarios/return-youtube-dislike/blob/main/Docs/FAQ.md)

### Like-count correction (0.4.1)

The player now carries NewPipe's public like count in its source-neutral video
model instead of discarding it during stream extraction. It uses that count if
TV account metadata omits likes, including during signed-out playback or account
metadata errors. Counts use the feed's compact K/M style; unknown/hidden counts
remain a dash and known zero remains zero. Feed cards themselves show views,
which are never substituted for likes. TV transport-control count text is also
supported even when it lacks a like action endpoint.

Debug assembly, lint and all 70 offline unit tests passed, including public-count
fallback, blank account fields, zero/unknown counts, account count precedence and
TV transport count parsing. A separate manual, anonymous public extraction probe
for `aqz-KE-bpKQ` returned 110,336 likes; no automated test contacts YouTube. APK
signature and alignment checks passed and version 0.4.1 was installed on the Pixel.

### Collapsible controls and comments (0.4.2)

The player title opens a **Details and controls** dropdown, collapsed by default
for each newly selected video. It contains quality, ratings, subscription actions
and sync/estimate toggles. Expanded controls scroll within a bounded area so the
community panel stays accessible. Playback errors and retry remain visible even
when details are collapsed. Landscape/PiP continue showing the video alone.

Below the dropdown, **Comments** opens by default. Swipe left to **Related videos**
and right to return; either tab can also be tapped. Selecting a different video
returns to Comments. Each tab scrolls vertically independently. Comment reading
uses anonymous NewPipe extraction behind VideoRepository, without account tokens.
It displays authors, avatars, text, date, likes, pinned/creator-heart labels and
expandable long text. Load more and retry preserve existing comments; disabled,
empty, loading and error states have separate messages. Posting, replies and
comment voting are not added in this update.

Jsoup 1.22.2, already present at runtime through NewPipe, is explicitly available
at compile time to turn optional comment HTML into plain text. No HTML is run in
a WebView. Comment requests and mapping run on IO. Changing/clearing playback
cancels pending requests and clears the previous comment list.

Verification: debug assembly, lint and all 77 offline tests passed. Added tests
cover comment mapping, HTML/entities, missing metadata, stale-result cancellation,
video changes, disabled comments and paging failure/retry/deduplication. A separate
manual anonymous probe loaded 20 public comments plus a second page of 20 for
`aqz-KE-bpKQ`, with no extraction errors. Automated tests make no live requests.
APK signature/alignment checks passed and 0.4.2 was installed with existing data
preserved. The Pixel was locked afterward; dropdown/gesture acceptance on the
physical screen remains for the owner to check.

Extractor APIs verified against the pinned release:

- [CommentsInfo paging API](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/comments/CommentsInfo.java)
- [Comment item fields](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/comments/CommentsInfoItem.java)
