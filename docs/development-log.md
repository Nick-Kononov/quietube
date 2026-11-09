# Development log

Phase-by-phase notes, verification records and design decisions, moved here from
the original README. Newest entries are at the bottom.


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
Quality defaults to 1080p on unmetered Wi-Fi and 720p on other connections (a phone hotspot counts as metered) when selecting
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
are encrypted with an Android Keystore AES-256-GCM key in `noBackupFilesDir` (keys created
before 0.5.3 are 128-bit until the next sign-out).
WebView cookies/storage/cache are cleared after capture or cancellation; the
login activity is not exported, disables debugging/file access and prevents
screenshots. The app never logs session cookies or authorization headers.

Home, subscriptions, history, Watch Later, liked videos and playlist tabs use a
custom InnerTube `AccountSource`. Library playlists open through the same
repository. Parsers support legacy renderers and lockup view models, skip
malformed entries and support infinite continuation paging plus a Load more
button. Home and History hide Shorts. Signed-out home uses a local feed built
from anonymous search results, also without Shorts.
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
playback is cleared on account sign-out. DataStore contains sync preferences,
selected feed/playlist UI state and up to 20 recent search queries. Feed UI
preferences and recent searches are cleared on sign-out. The separate
`appearance_preferences` store (System/Light/Dark and opt-in wallpaper colours)
survives sign-out.
No account secrets are stored in DataStore.

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

For daily use, install the optimised release build instead. It is shrunk with R8
(about 4 MB instead of 27 MB) and runs Compose without debug instrumentation, so
lists scroll noticeably smoother. It is signed with the local debug key, so it
installs over a debug build in place and keeps the signed-in session. Debug-only
tools (the `video_url` launch extra, `QuietubePerf` logging, `run-as`) are
unavailable in it. NewPipe and Rhino keep-rules live in `app/proguard-rules.pro`.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
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

1. Install the APK and open **YouPoop**. Search for a normal 1080p video.
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

NewPipeExtractor **v0.27.0** (released 2026-11-07) was verified with the GitHub
releases API; it adds the comment-avatar fallback (#1525). Dependencies are
pinned. The links below still point at v0.26.5 sources; those APIs are unchanged
except that `Description` is now a record with a nested `Type`.
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

5. Watch Later add/remove, comment posting, SponsorBlock, downloads and settings.

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
format, and select that download in YouPoop's Import cookies.txt picker. A
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
return to YouPoop. Google may identify the client as **YouTube on TV**, rather
than YouPoop. This prototype uses the published TV client's configuration,
discovered from YouTube's current TV page and script; it does not use a private
YouPoop Google Cloud client. No computer, cookie-export extension, Play Services
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
are restricted to HTTPS YouTube stats paths and are never logged or persisted.
Device-code sessions send them to `www.youtube.com` with the account's bearer
token (without it YouTube accepted the pings but did not record history);
cookie sessions send them without credentials. Reporting preserves the returned tracking parameters,
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
update. History was confirmed on a device-code account in 0.5.4; cookie-session
history and mutation acceptance remain unverified, and a successful HTTP response
alone is not proof that YouTube retained progress.

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

### Playback throughput fix (0.4.3)

YouTube's media servers throttle open-ended byte ranges to roughly real-time
speed. YouPoop fetched each split video/audio stream as one open-ended request,
so a 1080p60 stream (6.9 Mbps) downloaded at 5.6–6.1 Mbps and playback stalled
repeatedly. `ChunkedDataSource` now reads progressive `googlevideo.com` streams
(those advertising `clen`) as consecutive bounded ranges of at most 10 MB, like
yt-dlp and NewPipe. Streams smaller than one chunk, typically audio, also get a
bounded range: the throttle applies to any open-ended request and holds audio near
real time, which caused a 24.7 s stall before this was fixed. Manifests and live
segments without `clen` pass through unchanged.

Measured for the same 1080p stream: 6.1 Mbps open-ended vs 130.7 Mbps chunked on
the Pixel 8 (Wi-Fi), and 5.6 vs 325 Mbps from the development Mac. A 60 s 1080p60
playback run on the Pixel afterwards had no stalls and no dropped frames; video
chunks loaded at 61–108 Mbps. Cold start was 3.6 s to resolve and 5.7 s to the first
frame. Transfers while the 50 s buffer is full appear slow in `transfer` logs
because the player pauses reading, not because of throttling.

Also in this update: comment extraction starts after the stream resolves instead
of competing with playback startup; player state no longer emits a UI update for
every player event; picture-in-picture parameters are only sent to the system
when they change.

Debug builds log startup and stall timings under logcat tag `QuietubePerf`
(`resolve_ms`, `first_frame_ms`, `stall_ms`, and a summary every 15 s of
playback). No URLs or video IDs are logged:

```sh
./.tools/android-sdk/platform-tools/adb logcat -s QuietubePerf
```

Device tests in `ChunkedDataSourceDeviceTest` use an in-memory source. The live
throughput comparison runs only when requested:

```sh
./.tools/android-sdk/platform-tools/adb shell am instrument -w -e live true \
  -e class 'dev.quietube.playback.ChunkedDataSourceDeviceTest#liveThroughputOnThisDevice' \
  dev.quietube.test/androidx.test.runner.AndroidJUnitRunner
```

## Wi-Fi prefetch and rating buttons (0.5.0)

On Wi-Fi, videos that stay visible for 400 ms in Home/feeds, search results or the
Related tab are resolved before they are tapped (`PlaybackPrefetcher`), so a tap
can start loading media immediately. At most six visible videos are queued and two
resolve at a time; scrolling replaces the queue. A tap reuses a finished or
in-flight result. Results are kept for one hour (stream URLs last about six) and at
most 40 are kept. Failures are not cached, a tap that shared a failed prefetch makes
its own attempt, and a rate-limit response pauses prefetching for ten minutes
without affecting taps. If prefetched stream URLs are rejected, for example after
moving from Wi-Fi to mobile data, the existing retry re-extracts instead of reusing
the cache. Nothing is prefetched on mobile data or metered Wi-Fi such as a hotspot.

Like and dislike buttons now sit under the video title instead of inside the
collapsed details. They show counts in compact form (dislike estimates are marked
`≈`) and are enabled when signed in; tapping the active rating removes it.

## Mini player and pull-to-refresh (0.5.1)

Leaving the player while a video is active shows a mini player above the bottom
edge of every screen: live video, title, channel, play/pause and a close (×)
button. Tapping it reopens the full player. Close saves resume progress, sends the
final watch-history interval and stops playback. Picture-in-picture still starts
from the full player when leaving the app.

The player title now opens and closes the details section by itself; the
"Details and controls" label is gone (the chevron remains).

Home and the other feeds refresh by pulling down. The current items stay visible
until fresh results arrive, and a pull skips the 30-second feed cache. The Refresh
button was removed.

## Feed fixes and comment pull-to-refresh (0.5.2)

- History was empty: on the TV client its tiles open Shorts with
  `reelWatchEndpoint`, which the parser ignored. Those tiles are now videos.
- History stopped after the first page: TV continuation responses also contain
  a stub `contents`, and the parser read that instead of `continuationContents`.
  Paging now continues. The fix applies to every TV feed; only History was
  checked on the device.
- Playlists showed unrelated videos: the TV client remapped
  `FEplaylist_aggregation` to `FEmy_youtube`, a mixed overview page. It now
  requests the owner's playlists directly, and playlist tiles that open via
  `browseEndpoint` `VL<id>` are parsed with their video count.
- Back from an opened playlist returns to the playlist list instead of leaving
  the app.
- The feed name heading was removed (the selected chip already shows it); an
  opened playlist still shows its title.
- Comments refresh by pulling down, keeping current comments visible; the Refresh
  button was removed.

## Feed and account fixes (0.5.4)

- Watch history was not recorded: YouTube accepted the anonymous tracking pings
  but did not add the videos to History. Device-code sessions now attach the
  bearer token. Verified on the device: a video played in the app appeared
  first in History. History had looked wrong because it showed only what other
  devices had recorded, mostly Shorts (which stay hidden).
- History skips up to five pages of Shorts at a time, up from three.
- Playlists showed an endless spinner: the TV client returns a continuation that
  repeats the same playlists. Paging stops once a page adds nothing new.
- Pull-to-refresh on Home lists videos you have not seen yet first, fetching up
  to two more pages when needed, then the earlier videos in shuffled order. The
  list returns to the top. YouTube's TV Home changes only slightly between
  requests, so without this the top of the feed looked the same.
- Your account avatar replaces the Account button and appears with your name on
  the account screen (`account/accounts_list`).
- Comment avatars load again after upgrading NewPipeExtractor to v0.27.0.
- The app name in the header is written "YouPoop", with the logo to its left on
  Home and Search. The wordmark uses the system's bold condensed Roboto, the
  closest free match to YouTube's proprietary YouTube Sans. Body text was
  already Roboto, as in YouTube; text colours now match YouTube's dark theme
  (#F1F1F1 primary, #AAAAAA secondary).
- Screen transitions follow Android instead of crossfading (`ui/ScreenMotion.kt`).
  Opening a screen slides it in from the right while the previous one shifts
  left and dims; the back arrow reverses it. The back gesture reproduces the
  system cross-activity animation that Settings uses, with values taken from
  AOSP `DefaultCrossActivityBackAnimation`. While dragging, the screen scales to
  90% and (left-edge swipe) moves to 8dp from the right edge, never further; the
  previous screen waits 96dp to the left at the same scale under an 80% black
  scrim. After release the screen fades out over the first 90ms of a 450ms
  emphasized animation as the previous one grows to full screen; cancelling
  springs back. Progress comes from the NavigationEvent dispatcher, while NavHost
  still performs the pop.
- New round logo from `art/logo.svg`. The SVG wraps a 426×426 PNG whose poop
  shape is a transparent cutout; the app fills it white and ships it as bitmaps.
  The launcher icon uses the logo's red (#C10800) as its adaptive background and
  sizes the circle to the 72dp visible area, so any launcher mask is filled to
  the edge. `drawable-nodpi/ic_quietube.png` is the header logo.
- The player has no "Now playing" bar: the video sits at the top, and Back, Play
  on TV and picture-in-picture overlay it, shown and hidden with the player
  controls.
- **Play on TV** (`cast/`): finds TVs on the Wi-Fi with SSDP/DIAL and plays the
  current video, from the current position, in the TV's YouTube app, then pauses
  the phone. YouTube's phone app does it the same way: a DIAL launch with a
  one-time pairing code, which YouTube exchanges for the screen's lounge token,
  then a Lounge (MDX) `setPlaylist`. Lounge is not a public API and may break.
  The TV plays under its own account, so ads appear unless that account has
  Premium. DIAL is plain HTTP, so those few requests use a socket limited to
  private IPv4 addresses, and cleartext stays disabled app-wide. Android 17 asks
  for the nearby-devices (local network) permission the first time. Verified
  with a Hisense VIDAA 55Q6Q.
- **Direct from phone** (experimental, no ads): for TVs with a DLNA media
  renderer, the phone serves the video itself. It downloads YouTube's separate
  H.264 (up to 1080p) and AAC tracks and remuxes them on the fly into one
  Matroska file. Then it sends the file's address to the TV with UPnP
  AVTransport. Only the TV's address may connect, at a random path. A
  foreground service (`StreamService`) keeps the phone streaming with the screen
  off and has a Stop action. Lounge seeks in YouTube-app mode are implemented but
  not yet tested.
  - TV players seek by byte offset, so the stream poses as a complete file of
    fixed size. Its header has the segment size, the duration and Cues. Byte
    offsets in the cluster area map linearly to time, and a request for any
    offset is answered with clusters from the keyframe for that time.
  - The Hisense ignores the Cues and estimates the offset from size and
    duration, which the linear map matches. It counts its clock from the first
    timestamp it receives, so the file starts at 0:00. Once frames are showing,
    the phone seeks the TV to the cast position.
  - While the TV plays, the phone's seek bar shows the TV's position, polled
    each second. Seeks and ±5/15s on the phone move the TV, and the paused phone
    player loads nothing. Pressing play or Stop on the phone continues there
    from the TV's position.
  - Verified on the Hisense: playback, pause and resume, screen off, and
    AVTransport seeks from the phone's seek bar and +15s button. Seeks to 2:00,
    5:00 and 20:00 were served from 119s, 299s and 1199s, and the TV reported
    matching times. Seeking with the TV remote is still to be checked by hand.

## UI refresh — Phase 1

Account now has System (default), Light and Dark appearance controls, and an
opt-in wallpaper-colour switch on Android 12+. Appearance lives in its own
DataStore and is not part of account cleanup. It applies to the current screen
and mini player without recreating the activity or restarting playback.

The full light/dark palettes were generated from #FF0033 using Material Color
Utilities 0.3.0 (Tonal Spot), with near-black dark surfaces and stronger action
reds adjusted for contrast. The generated constants ship in the app; no colour
library or network dependency was added. All Material 3 colour roles, Roboto
text styles and corner shapes are explicit. Video surfaces remain black and
video overlay icons and thumbnail badge text remain white. Predictive-back
geometry and its black scrim retain the existing platform animation.

Android 12+ remembers the app's light/dark launch mode for the native splash.
Older Android versions disable the starting-window preview and wait for the
saved appearance before drawing, avoiding a mismatched system-theme flash.
The launch background is neutral rather than wallpaper-derived. The adaptive
icon now has a monochrome vector traced from the original logo's alpha cutout.

Verification and remaining device checks are recorded in
[the Phase 1 report](ui-refresh/phase1-report.md).

## UI refresh — Phase 2

Video cards now show a thin watched-progress bar when progress and duration are
known: hidden below 2%, full from 95%, and hidden for live streams. Feed, search
and related results use saved Room progress when available, falling back to the
progress supplied by YouTube. Results read progress when they load. Each card
exposes one accessibility label including its metadata and watched percentage;
the thumbnail is decorative. The player title announces its expanded state.

YouPoop accepts HTTPS video links for youtube.com, www.youtube.com,
m.youtube.com and youtu.be, including watch, shorts and live URLs. Sharing plain
text containing a YouTube video link also works. Links are validated and reduced
to a canonical watch URL; supported t/start timestamps override saved resume
positions for that launch. Other hosts, userinfo, malformed IDs and invalid
timestamps are rejected with a snackbar. Tracking parameters are discarded.
The app does not claim domain verification: Android's link settings/defaults
control which app opens these domains. The debug-only video_url extra stays
restricted to debuggable builds, and recreation does not replay launch intents.
History reporting still follows the existing sync preference.

Account, engagement, history-sync and cast messages use consumed snackbars;
errors with retry and empty/disabled content states stay inline. Feed and search
share skeleton cards with a subtle shimmer that stops when system animations
are disabled. No new service, network integration or dependency was added.

Verification: debug assembly, all 115 JVM tests and lint passed (zero errors,
41 existing warnings, no new warnings). On the Pixel 8, VIEW at 90 seconds,
SEND at 120 seconds, invalid-link feedback, watched progress in feed/search,
and merged card accessibility labels were checked. See
[the Phase 2 report](ui-refresh/phase2-report.md) for file references, screenshots
and the remaining device checks.

## UI refresh — Phase 3

Settings is now a separate screen, reached from Account, the feed/search account
menu and video details. It contains appearance, playback notes, watch-history
sync, the opt-in Return YouTube Dislike setting and its data disclosure, and
version/licence information. SponsorBlock and DeArrow are listed as unavailable;
no integration or network requests were added for them. Existing preference
stores and defaults are unchanged. Appearance still survives sign-out.

The player title opens a partially expanded description sheet with channel,
views/date, quality, subscription actions, Settings and the extractor's available
description as selectable plain text. HTML is converted locally with Jsoup;
no WebView or additional request is used. The previous inline details panel and
its sync switches are gone. Quality uses radio rows in a bottom sheet, retaining
the current position when changed. Play on TV also uses a sheet with scanning,
separate YouTube-app/direct options, Search again and the existing explanation.
The local-network permission flow is unchanged.

Account has a large app bar, a connected profile and action list when signed in,
and a primary Google sign-in action when signed out. Device-code sign-in stays
experimental; its pending code and browser/copy/cancel actions appear in a card.
The clipboard remains sensitive on supported Android versions, and browser
launch still prefers Chrome then falls back. Sign-out now asks for confirmation
before its existing cleanup. Sign-in explanations expand on demand. Playlist
rows use Material list items with a 16:9 cover.

Verification: debug assembly, all 120 offline JVM tests and lint passed (zero
errors, 41 existing warnings, no new findings). Pixel checks covered Settings
navigation/live appearance, Account and sign-out cancellation, description and
quality sheets, position retention on 720p selection, and discovery/options for
the Hisense TV. No actual cast or sign-out was performed. See
[the Phase 3 report](ui-refresh/phase3-report.md) for evidence and remaining checks.
The proposed Phase 4 navigation was approved and is implemented below.

## UI refresh — Phase 4

Home, Subscriptions and Library are now the top-level destinations. Library opens
History, Watch Later, Liked videos and Playlists in one more tap. Signed-out
Subscriptions and Library offer sign-in instead of disabling navigation. Material
3 NavigationSuiteScaffold shows a bottom bar on phones and a rail from 600dp of
window width. The player hides the navigation; its mini player sits above the
phone's bottom bar. Expanded search temporarily fills the window and hides the
navigation and mini-player dock while playback continues.

FeedViewModel keeps loaded feed/playlist pages, cursors and retry state in memory
for the current account. Each feed and opened playlist has separate saveable
scroll/app-bar state. Re-tapping the selected destination scrolls to the top and
refreshes its feed. Library's static list only scrolls to the top. Top-level
navigation closes search/account/settings overlays; Library consistently opens
its list. Back from a top-level Subscriptions or Library screen returns to Home;
Library's child feeds and opened playlists retain their normal back steps.
All routes continue through the existing ScreenCard and predictive-back motion.

Feed app bars hide on upward scrolling and return on downward scrolling, with
pull-to-refresh retained. Account and Settings have collapsing large app bars.
Search uses a full-screen expandable Material search bar, recent-search rows and
Clear history, with the existing result cards, paging, suggestions and error
states. Recent searches stay in the local `search_history` DataStore, newest
first, capped at 20 distinct queries (500 characters each). Clearing history or
signing out removes them. Restoring an existing query does not add it back to
cleared history; unfinished drafts also survive process restoration.

The navigation-suite dependency uses version 1.3.2 from the existing Compose BOM;
no BOM upgrade or new service integration is included. Account networking,
stream URL handling, casting and playback-service logic are unchanged.

Verification: debug assembly, all 130 offline JVM tests and lint passed (zero
errors, 41 existing warnings, no new findings). Pixel checks and screenshots are
recorded in [the Phase 4 report](ui-refresh/phase4-report.md). Phase 5 has not started.

## UI refresh — Phase 5 (0.5.5)

The app now uses Material 3 Expressive motion, connected rating toggles and
filled/tonal subscription controls with shape and colour morphing. Accepted
rating/subscription actions emit one Confirm haptic; recomposition, refresh and
network rollback do not. Actions update immediately, serialize requests and
roll back on failure with Retry. Unsubscribe offers Undo. Notice IDs, video
context and account revisions prevent stale responses or snackbar actions from
modifying a different video/account. The EngagementViewModel owns all action
state; no new account storage or service integration was added.

Indeterminate circular loaders use Expressive loading shapes, including
pull-to-refresh. Remove animations switches them to static shapes and also
stops skeleton shimmer. The observer reads the setting directly to avoid stale
ValueAnimator cache values during a live setting change. Appearance settings,
privacy restrictions and ScreenMotion's navigation/back timings are unchanged.

Compose BOM is pinned to 2026.09.00 and Material 3 to 1.5.0-rc01. The owner
approved the release candidate because stable 1.4.0 lacks the required APIs.
The upgrade also migrates deprecated list, tab, sheet and search APIs. Search
keeps its existing route/results/history and now uses TextFieldState with
one-way draft updates, avoiding lost characters from a feedback loop.

Verification: debug/sample assembly, all 143 offline JVM tests and lint pass
(zero errors, 41 existing warnings, no new findings). Pixel checks cover
anonymous playback, PiP, mini-player return, search typing/submission and an
edge-back commit. Reduced-motion loading captures match pixel-for-pixel.
A separate samples variant provides reproducible light/dark captures of the
production controls using synthetic data; it does not read the owner's session.
See [the Phase 5 report](ui-refresh/phase5-report.md) for screenshots, exact scope
and remaining manual acceptance. Phase 6 has not started.

## Phase 5 review fixes (0.5.6)

The profile button now opens Account directly; Settings stays on Account. The
persistent signed-out rating note is removed. Attempting to tap a disabled
rating shows a short sign-in snackbar, without enabling the action; the same
explanation is available as an accessibility action. Successful watch-history
session starts and progress reports are silent. Failures still report that sync
was not confirmed. The video buffering overlay returns to a white circular
spinner; the mini player and other loaders retain Expressive indicators.

Predictive-back completion now keeps the outgoing screen hidden until NavHost
removes it. Previously our 450 ms animation reset its alpha while Navigation
could retain the outgoing layer for its 1000 ms hold, revealing it again. The
change preserves the gesture geometry and timings and supports another back
gesture while the previous layer is still settling.

Thumbnail loading was separate from playback prefetch: images only started
loading when their cards appeared. Feeds, search and Related now warm two covers
ahead and one behind through the existing anonymous Coil loader and cache, at
the card's decoded size. Work is deduplicated, bounded to three requests and
cancelled when no longer relevant. Speculation is restricted to unmetered Wi-Fi;
visible images keep their existing network behaviour. Sign-out cancels pending
look-ahead before wiping the image cache. No new dependency, credentials or
network integration is involved. Outgoing search/player callbacks can no longer
replace the active feed's playback-prefetch queue; Related only prefetches while
its tab is selected.

Six new offline tests cover cover selection, unknown/skeleton keys, image
fallbacks, request reuse/cancellation, network policy, bounds and size changes.
All 149 JVM tests pass with no new lint findings. Native checks on the Pixel
confirm tap-only feedback, direct Account/Settings navigation, short/long
edge-back completion and an offscreen 960 × 540 decoded thumbnail in memory.
Updated synthetic light/dark samples are included in the Phase 5 report. The
release variant is used for the final install to avoid debug rendering overhead.

## Dark Settings flicker investigation

The owner noticed a slight brightness flicker on empty-area taps in dark
Settings on the Pixel 8. In the separate signed-out installation, a 15,000-pixel
background region stayed exactly #101215 before and after tapping the empty
Settings header. Display diagnostics instead showed the physical mode switching
60 → 120 → 60 Hz on touch. Holding 120 Hz kept the mode stable, and the owner
confirmed the visible flicker stopped. The temporary refresh/stay-awake overrides
were restored after comparison; the owner chose to retain adaptive refresh.

This was a display refresh-rate effect rather than an app background-colour
change. No theme, ripple, navigation or playback change was made for it. A
phone-wide fixed refresh rate is a workaround with a battery-use tradeoff;
it is kept separate from the app defaults. Source and test results remain those
of 0.5.6.
