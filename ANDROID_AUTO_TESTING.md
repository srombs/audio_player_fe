# Android Auto foundation test guide

Steps 1–12 and step 13's lifecycle fixes/automated coverage are implemented.
Main and instrumentation sources compile, all 185 JVM
tests pass, the debug APK builds, and Android lint passes. The user confirmed DHU
connection, issue browsing, audio playback, and the ticket 05 rewind fix.
Device regression checks for steps 7–13 and connected instrumentation execution
remain pending.

## Build and automated checks

From this repository, first configure the backend URL using the
[README build instructions](README.md#open-and-run):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug
```

The JVM suite covers the tree, pagination, cached restart, stale refresh,
overlapping requests, narration filtering, controller policy, and artwork disk
bounds alongside the existing networking/playback/progress tests. Selection tests
also cover latest-request wins, noncooperative late responses, callback
cancellation, shutdown, failed replacements, and resolver identity mismatches.
The progress/resumption/recovery tests cover v1 cache migration, speed persistence,
shortened retries, independent last-played metadata, completed resumption at zero,
fresh resolution after restart, actionable error codes, and bounded retry budgets.
New tests cover v2-to-v3 metadata migration, offline Continue Listening, recency,
completion removal, the five-issue search boundary, title/author normalization,
ambiguous queries, offline search errors, per-caller result snapshots, controller
detachment, request ownership, and following car-selected player navigation.
Completion-history tests additionally cover persistence without signed URLs,
duplicate ended samples, replay recency, independence from unfinished progress,
filter ordering, corrupt-store recovery, and offline metadata lookup for history.
Regression coverage includes saved seek-bar progress after refreshing an issue,
preserving newer durations and confirmed narration absence, and persisting the
global listened filter across app restarts. Navigation device tests check filter
selection across different issues and restored navigation state.
Duration regression tests cover merged repository responses, oversized issue
responses, car catalog refresh/restart, and duration labels on browse nodes.
Mapper instrumentation cases cover duration prefixes before long descriptions,
unknown/nonpositive durations, and preservation of article identity/metadata.
Issue-progress regression coverage includes distinct eligible membership before
display caps, summaries from 125-entry responses, all 200 retained memberships,
restart/offline access, full-list eviction, old/corrupt index rebuilding,
multi-ID completion updates, replay, bounded hydration, and newer narration
resolution winning over a late browse response. The issue mapper device case
checks fractions in both title fields while preserving the underlying issue
title and ID, including a long title.
Review regressions also cover narration changes from browse metadata reaching
all shared issues after detail eviction, and completion notifications for
legacy cached article parents while exact membership remains unavailable offline.
Ticket 04 adds catalog coverage for the All / Unlistened / Listened folder tree,
filtering before pagination, invalid IDs/pages, empty results versus errors,
offline restart and unavailable membership, artwork refresh, and filter-specific
counts across shared issues and all 200 retained memberships. The new real-browser
smoke case seeds a fresh fictional catalog through existing storage, checks folder
metadata/lookup/errors, subscribes to the five affected parents, and checks
completion notifications and unchanged session state. It does not start audio;
completion while an article is actively playing remains a host acceptance check.

With a phone or emulator connected, run the new device smoke tests:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rombs.newyorkeraudio.playback.MediaLibrarySmokeTest,com.rombs.newyorkeraudio.playback.ArtworkCacheTest,com.rombs.newyorkeraudio.playback.SelectionPlayerTest,com.rombs.newyorkeraudio.playback.PlaybackResumptionTest
```

These tests connect to the real service without a phone activity, browse its
local root, check command availability, read fallback artwork through the
provider, reject writes/invalid paths, and normalize a fixture image through an
intercepted HTTP client. They need no live backend or actual car host. Stop any
existing narration before running the root test, which expects an idle session.
`SelectionPlayerTest` additionally checks Pause while already paused, Stop, all
Media3 set-item variants when reusing the active source, explicit seeking, and
replacement with a fresh source. These tests do not fetch audio.
Additional player tests check saving before Stop/replacement, seeking to zero
while idle, and suppressing fallback Play after failed resumption. The real
Media3 cold-Play test verifies controller-request context and cancellation without
fetching a source. Recent-content browsing checks metadata-only replies and
unavailable next/previous-track commands.
The service smoke suite also verifies that rejected searches finish and that
disconnecting one controller leaves another bound controller usable without
changing the active item or Play intent. These device tests have not run here.
The ticket 04 cached-catalog smoke case temporarily replaces the persisted catalog
and adds a synthetic completion. It restores both stores afterward; the synthetic
entry remains in the application singleton until the test process exits. Run the
suite in a test installation with playback stopped.

## Prepare the phone and Desktop Head Unit

Install the debug app on a phone with an up-to-date Android Auto installation.
Open the app once and confirm phone playback against the Railway backend.
In Android Studio's SDK Manager → SDK Tools, install **Android Auto Desktop Head
Unit Emulator**. Connect the unlocked phone over USB and enable USB debugging.

Enable Android Auto developer mode and start its head-unit server. Forward the
connection and start the DHU on macOS. Set `ANDROID_HOME` to your Android SDK
directory (shown in Android Studio under SDK Manager) before running these commands:

```sh
"$ANDROID_HOME/platform-tools/adb" devices -l
"$ANDROID_HOME/platform-tools/adb" forward tcp:5277 tcp:5277
(cd "$ANDROID_HOME/extras/google/auto" && ./desktop-head-unit)
```

Alternatively, DHU 2.x supports `./desktop-head-unit --usb`. Keep the companion
libraries beside the executable. Follow the
[official DHU setup guide](https://developer.android.com/training/cars/testing/dhu)
for developer-mode and connection details.

## First head-unit check

| Check | Expected behavior |
| --- | --- |
| Discovery | New Yorker Audio appears in the media app list. |
| Connection | The car connects while the phone activity is closed. |
| Root | Issues and Continue Listening appear promptly; connecting does not start audio. |
| Issue grid | Issues requests a grid of magazine covers, newest first. Loaded membership adds a fraction such as `3/12 listened` to the display title. Unloaded progress has no fraction and uses `Progress unavailable` in the subtitle where the host permits. Check long titles and live updates; the host controls columns and tile size. |
| Filters | Selected issue shows All, Unlistened, Listened in that order. Each title carries the same whole-issue fraction, such as `Unlistened · 3/12 listened`. Check the extra navigation level with touch and rotary controls, plus actual heading visibility when opening each folder. |
| Articles | Selected filter shows article titles/descriptions and thumbnails in source order. Known duration precedes descriptions; completed rows start with Listened, followed by known duration. Unknown durations are omitted. Known unnarrated articles are excluded. Android Auto controls description truncation. |
| Filter completion | While playing an Unlistened article, complete it from phone or car. It leaves Unlistened, appears in Listened, and updates folder/magazine fractions. Playback, position, pause state, and speed continue normally. Replaying it keeps its listened status. |
| Selection | Choosing a narrated article resolves its ID and starts playback. |
| Metadata | Now Playing shows the selected title, author, and local artwork. |
| Controls | Play/Pause and seeking work through the shared session. |
| Phone closed | Press Home/Back on the phone; car playback and controls continue. |
| Phone return | Opening the phone player shows the current car-selected article and progress. |
| Cached browsing | After loading an issue, disconnect networking and reopen that issue. Cached metadata remains browsable. Streaming still needs its source/network. |
| Empty vs unavailable | An empty issue still offers three folders with `0/0 listened`. Empty filters return successful empty lists. Unknown membership uses `Progress unavailable` without a numeric fraction. A cold metadata failure reports an error with retry. |
| Filter independence | Change car folders and confirm the phone's saved filter is unchanged. Search, Continue Listening, and recent items retain their own results. Reconnect and reopen all three folders with the phone backgrounded. |
| Reconnect | Disconnect/reconnect the DHU; connection alone does not autoplay. |
| Phone regression | Phone selection, pause, notification/lock-screen controls, progress restoration, and fresh-URL retry still work. |

For a second input-mode check, start `./desktop-head-unit -i rotary` and navigate
using the DHU's controller controls. Then run the Continue Listening, voice, and
lifecycle checks below.

## Connection diagnostics

```sh
"$ANDROID_HOME/platform-tools/adb" -d logcat -T 1 -v brief -s PlaybackAccess:I PlaybackBrowse:I '*:S'
```

`PlaybackAccess` records session selection, the package, UID, and connection decision, without
subscriber information or audio URLs. Android Auto access validates the installed
UID and a published production signing certificate. Own-UID and framework-trusted
controllers keep access; private phone selection commands require the app's UID.
Session selection in `onGetSession` does not authorize callers: Media3 1.8.0
provides a synthetic legacy controller with UID `-1` when creating the browser
binder. Actual caller authorization runs in `onConnect`, after binding. Rejecting
the placeholder prevents Android Auto from reaching that check or browsing.
Certificate references are linked from `ControllerAccessPolicy.kt` and
[Google's caller list](https://github.com/android/uamp/blob/main/common/src/main/res/xml/allowed_media_browser_callers.xml).

`PlaybackBrowse` records root replies and numbered child/item requests, including
completion, item counts, errors, and timeouts. Browse requests have a 15-second
deadline so stalled loads report an error. Search commands are enabled. Search
logs record query length rather than the spoken text. The service notifies search
completion for both successful and failed results so the legacy host can finish
its pending request.

## Steps 11–13 phone/DHU checks (pending)

Install the latest debug APK and confirm `diagnostics=dhu-voice-13` in the filtered
logs. These checks require a phone/DHU; they have not been run in this workspace.

| Check | Expected behavior |
| --- | --- |
| Continue Listening | Play two articles partway, then open Continue Listening. Most recently updated comes first, with progress metadata. |
| Restart/offline list | Restart the app with networking disabled. Saved titles and progress remain visible without browsing their issues first. Audio streaming still needs connectivity. |
| Completion | Finish an article. It disappears from Continue Listening; last-played resumption remains separate and starts completed audio from zero. |
| Title voice request | Ask to play a unique title from one of the five newest issues, before opening that issue on the phone. It resolves and plays through the shared service. |
| Author/ambiguity | Search an author's name. Multiple matches must not start an arbitrary article; use a specific title to play. |
| Empty voice request | Resume the newest unfinished article, otherwise last played. With neither, report no saved article. |
| Search failure | Unknown title or unavailable network finishes with an error/empty response, preserving existing playback and avoiding an indefinite spinner. |
| Closed phone | Start browsing/playback from DHU with the phone activity closed. Library and controls remain usable. |
| Home/lock during resolution | Start a phone Play request, immediately press Home or lock. The explicit request may finish; no closed-screen callback or unwanted cancellation occurs. |
| Rotation | Rotate during selection and playback. Audio and saved position survive; recreation does not replay a voice intent. |
| Competing selections | Start a slow phone selection, choose another article in the car, then leave the old phone player screen. The newer car request wins. |
| Phone return | Return to a previously active phone player after changing articles in the car. It shows the car-selected article; deliberate browsing of another article remains intact. |
| Disconnect/reconnect | Disconnect DHU and reconnect. Connection alone does not autoplay or replace the active source. |
| Process recreation | While paused, background the phone and kill its background process, then reconnect/open. Metadata/artwork/progress survive; explicit Play resolves a fresh source. Force-stop is a separate OS restriction and is not normal process death. |
| Backend session renewal | Exercise a backend account failure and renew the session outside the car. Retry should work without a car login flow. |

## Local completion history checks (pending on device)

- Let an article reach the final 60 seconds or final 5% (whichever is smaller)
  on either phone or DHU. Its phone issue row
  should show Listened, and All / Unlistened / Listened filters should update
  immediately without refreshing the issue.
- Open History from the home toolbar. Check the finished article, description,
  finish date, and selection into the player. Opening this screen must not resolve
  audio or need a network connection.
- Finish a second article; restart offline. Both records should remain, newest
  completion first. History stays accessible if the issue catalog cannot load.
- Replay the first article. Its Listened flag stays set; completing it again moves
  its single record to the top. Starting or partially playing an article must not
  add it to completion history before the threshold. A five-minute recording
  qualifies at 15 seconds remaining; a 30-minute recording at 60 seconds remaining.
  Seeking into that region also counts. Confirm a near-end paused article retains
  its exact resume position in Continue Listening until it actually finishes.
- Check Android Auto's fully-played indicators in issue/search/recent entries.
  Replaying a completed article may still put it into Continue Listening while
  retaining its historical fully-played indicator.
- Run `ListeningHistoryScreenTest` and `MediaItemMapperTest` with the connected
  instrumentation suite for reactive filters, offline history UI, and car metadata.
  These tests compile in the workspace but have not run on a device.

## Phone voice-intent check

To exercise the phone voice-intent route, replace the query with an actual title
in the supported search scope:

```sh
"$ANDROID_HOME/platform-tools/adb" -d shell am start \
  -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
  -n com.rombs.newyorkeraudio/.MainActivity --es query "Article title"
```

This tests activity intent routing. It does not replace testing Android Auto's
voice/search controller path in DHU or a real car. Steps 14–15 cover the remaining
full host validation and release preparation.

## Step 7 phone/DHU regression checks

Install the latest debug APK (installation command below). Its startup marker is
`diagnostics=dhu-voice-13`.

| Check | Expected behavior |
| --- | --- |
| Rapid selection | Select A, then B while A is resolving. Only B takes over playback. Repeat between phone and DHU in both directions. |
| Pending Pause | Select an article over a slow connection, then Pause before resolution finishes. The late result cannot start playback. Repeat with an existing track paused and with no active track. |
| Pending Stop | Send Stop while resolution is pending. The callback cancels and playback stays stopped. |
| Failed replacement | While A plays, select a confirmed unnarrated or unavailable B. A is not replaced by B. |
| Same article | Seek and change speed on the phone, then select the active article in DHU. Position/speed stay intact and the source is reused. |
| After cancellation | Select another article after Pause/Stop. This new explicit selection can play normally. |
| Phone display | Let a car selection supersede a pending phone selection. The phone returns to shared session state without reporting the cancellation as a resolution error. |

The slow-response and shutdown races are covered deterministically by the JVM
tests; live behavior still needs checking through the installed phone/Auto host.

## Steps 8–10 phone/DHU regression checks

| Check | Expected behavior |
| --- | --- |
| Progress between phone and car | Seek/change speed on the phone, switch to B in DHU, then return to A. A resumes its saved position and speed. Repeat with car-originated seeks. |
| Pause/Stop and restart | Pause or Stop, restart the app/service, reconnect, and choose the article. Its final saved position survives. |
| Cold Play | After playing A, restart the process and reconnect DHU. Browsing alone stays silent. An explicit Play from the connected controller resolves and resumes A. |
| Completed article | Finish A, restart/reconnect, and explicitly resume. Last-played metadata still exists, but A starts at zero. |
| Empty history | With no prior playback, cold Play reports absence without inventing an article or setting an empty player to playing. |
| Recent browse offline | Recent metadata/artwork stays available with no network. Browsing does not resolve a streaming URL or change playback. |
| Controls | Check Play/Pause, scrubbing, and 15-second seeks. Host layout may vary; next/previous article navigation is not advertised. |
| Expired source | For a controlled expired URL returning 401/403/404/410, one automatic refresh occurs and position/speed survive. A second failure stops automatic recovery and offers manual retry. |
| Recovery cancellation | Pause/Stop or choose B while A's fresh URL is resolving. A's late response cannot start playback. |
| Audio interruption | Lose audio focus or unplug headphones during a pending source refresh. The delayed result must not restart playback. |
| Backend errors | A failed selection shows a useful connection/account/subscription/narration message in the host and preserves the previous item. |

The code supports cold Play from a connected controller. OS boot/background
resumption notifications are not enabled in this batch. Do not use a force-stop
followed by an unsolicited broadcast to test cold Play; reconnect the car/browser
first and issue an explicit Play. Do not delete app data when testing persistence.
Controlled source-expiry, shortened-recording, and empty-history cases may need
a test fixture; the automated tests cover their deterministic behavior.

### When only the old controller log appears

The current build includes `diagnostics=dhu-voice-13` in application startup,
activity startup, service creation/readiness, binding, and controller access logs.
These markers use `PlaybackAccess`, so a missing `PlaybackBrowse` tag alone does
not hide startup. No marker means the current build has not been confirmed.

From the repository root, install the APK produced by the build command above
explicitly (preserves app data):

```sh
"$ANDROID_HOME/platform-tools/adb" -d install -r "app/build/outputs/apk/debug/app-debug.apk"
```

Start the filtered log stream in one terminal before restarting the app:

```sh
"$ANDROID_HOME/platform-tools/adb" -d logcat -T 1 -v brief -s PlaybackAccess:V PlaybackBrowse:V '*:S'
```

In a second terminal, verify the filter with a shell-generated marker, then
force a fresh process and open the app:

```sh
"$ANDROID_HOME/platform-tools/adb" -d shell log -p i -t PlaybackAccess "ADB filter probe"
"$ANDROID_HOME/platform-tools/adb" -d shell am force-stop com.rombs.newyorkeraudio
"$ANDROID_HOME/platform-tools/adb" -d shell am start -n com.rombs.newyorkeraudio/.MainActivity
```

Expect the probe and `application started diagnostics=dhu-voice-13`, followed
by activity and service logs. Then reconnect DHU and select New Yorker Audio.
If the probe is absent, investigate the log stream/device selection. If the
probe appears but the version marker does not, check APK installation and activity
launch output. If phone markers appear but clicking DHU produces no additional
binding/controller/browse logs, investigate the Android Auto host connection.

## Test record

The user confirmed live DHU discovery, authorized host connection, issue browsing,
and audio playback on October 13, 2026. Record the phone/Android Auto/DHU versions
and mark the remaining checks above as passed or failed after each live run.
Compiled instrumentation tests have not been run in the coding workspace and do
not establish device-level validation of steps 7–13.

## Ticket 05 rewind acceptance

On November 10, 2026, the user confirmed **Back 15 seconds** on DHU after running
the Media3 1.9.1 build from Android Studio. The previous build showed only Play.
The updated phone session also exposed the rewind button.

For step 7, record the app commit and Android Auto, DHU, and car host versions.
Use a seekable narration and check the following on each target host:

- Rewind while playing and paused. The position moves back about 15 seconds
  within the same article, and playback state and speed are preserved.
- Rewind from within the first 15 seconds. The position clamps at zero.
- Rewind with the phone activity closed, then reconnect and confirm the control
  still works. Check that the phone shows the shared rewound position.
