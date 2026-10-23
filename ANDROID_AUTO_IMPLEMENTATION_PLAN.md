# Android Auto implementation plan

Prepared and revised after review on October 13, 2026.

Implementation status: steps 1–12 are implemented. Step 13's lifecycle fixes and
automated coverage are implemented; its physical phone/DHU checks remain pending.
The user confirmed DHU
connection, issue browsing, and audio playback after fixing legacy session
selection in step 6. Automated checks for steps 7–10 pass; their
selection, progress, cold-resumption, and recovery behavior needs a phone/DHU
regression check. The new Continue Listening, voice, and lifecycle behavior also
needs device validation. Steps 14–15 remain pending.

See [Android Auto test guide](ANDROID_AUTO_TESTING.md) for the device smoke tests,
DHU commands, expected results, and remaining live checks. DHU is installed and a
physical phone is available. Workspace ADB socket/USB access remains restricted;
live device checks are performed by the user.

Step 1 also adds a bounded persistent cache for 200 article summaries to keep
restored phone player pages usable without resolving audio during page loading.
This cache excludes stream URLs. Step 4 adds a separate car catalog snapshot with
a five-minute freshness window, up to 200 issue headings and 24 cached issues with
up to 100 articles each. Cached data remains browsable during an outage; an empty
result is stored separately from a failed request.

The app already has ExoPlayer, a background media session, backend audio
resolution, notification controls, and persistent progress. Android Auto needs
a browsable media library and playback commands that work without opening the
phone UI.

The proposed approach uses Android Auto's standard media interface, with the car
displaying issues, articles, and playback controls through Media3. Android Auto
supplies that interface. See the
[media browser overview](https://developer.android.com/training/cars/media/create-media-browser).

## 1. Define the shared playback contract and fix fresh-URL retries

Separate metadata lookup from audio resolution in the repository. The existing
`getArticle()` resolves audio; browsing, item lookup, and search results need a
metadata-only path backed by the catalog cache.

Add `refresh: Boolean = false` to the audio request model and an explicit
repository operation for resolving with `refresh: true`. Use it when recovering
from an expired or failed stream. Normal resolution should continue to use the
backend cache. This also fixes the existing phone retry path, which currently
cannot ask the backend to replace a cached URL.

Define one service-owned playback workflow for both phone and car: canonical
article ID, resolved source, start position, playback intent, and retry state.
Keep metadata-page loading separate from the user's request to play, and avoid
duplicate resolution requests between the phone ViewModel and service.

## 2. Upgrade the existing playback service

Change `PlaybackService` from `MediaSessionService` to `MediaLibraryService`, and
use `MediaLibrarySession`.

Keep the existing ExoPlayer instance, audio focus, background playback,
notification integration, and progress recording. Both the phone and car will
control this same session. The service must own audio selection and progress
restoration; Android Auto controls the session directly and would bypass the
current restoration code in `PlayerConnection`.

Implement the basic `onSetMediaItems` workflow to resolve an article ID and return
`MediaItemsWithStartPosition`. Respect an explicit requested position; otherwise
use the active session's position for the same article or its saved progress.
Keep player operations on its application thread and repository work asynchronous.

Phone controllers should send selection intent to this workflow rather than
independently replacing the player with a resolved streaming URI. A small working
playback path is needed for the early head-unit test in step 6; full recovery and
concurrency handling follow in batch 2.

Reference: [Media3 library service](https://developer.android.com/media/media3/session/serve-content).

## 3. Declare Android Auto support

Update the manifest with the Android Auto application metadata and the
library/browser service discovery actions. Add
`app/src/main/res/xml/automotive_app_desc.xml` declaring media support.

This makes the app discoverable as an Android Auto media app.

Reference: [Android Auto declarations](https://developer.android.com/training/cars/media/auto).

Register the Media3 library interface and the platform
`android.media.browse.MediaBrowserService` interface for legacy clients. Preserve
the phone's explicit service connection and existing media-playback permissions.

## 4. Build the browsing structure and fast metadata library

Proposed first version:

```text
New Yorker Audio
├── Issues
│   ├── October 19, 2026
│   │   ├── Article title — Author
│   │   └── Article title — Author
│   └── Earlier issues…
└── Continue Listening
    └── Articles with saved progress
```

Issues will be ordered newest first. Known unnarrated articles will be excluded
from the car's playable list. Articles with unknown availability can remain
selectable, with availability checked when selected.

Implement root, children, and individual-item lookup through
`onGetLibraryRoot`, `onGetChildren`, and `onGetItem`.

Give root and folder nodes their own namespaced identifiers. Issue nodes encode
the issue date; article identifiers remain the existing canonical article URLs
so navigation, progress, and backend identity stay consistent. Set each item's
browsable/playable flags explicitly. Support pagination, validate page arguments,
and return consistent ordering for repeated requests.

Return the library root immediately without network access. Media3's legacy
browser bridge may block the main thread while waiting for the root future;
simply returning a pending network future is insufficient.

Connect child loading to `NetworkNewYorkerRepository` asynchronously. Add a
bounded persistent catalog cache with an explicit freshness policy and shared
requests for overlapping loads. Prefer cached metadata while refreshing and
notify subscribed browsers when children change. Represent an unavailable
catalog separately from a genuinely empty issue.

Browsing and item lookup must not resolve every article's audio. Do not run the
playback `MediaItem` mapper on unresolved browse items: it requires a stream URL.
Use a metadata-only mapper for library entries.

The initial bring-up can expose Issues only. Add Continue Listening after the
required persistence and enumeration work in steps 8, 9, and 11.

References: [Library callback requirements](https://developer.android.com/reference/kotlin/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback),
[Media3 1.8.0 library callbacks](https://github.com/androidx/media/blob/1.8.0/libraries/session/src/main/java/androidx/media3/session/MediaLibraryService.java).

## 5. Deliver artwork through a local cache and content provider

Implemented artwork limits: 128 PNG files / 32 MiB, downloads up to 5 MiB,
decoded dimensions up to 512 pixels, and three simultaneous downloads. Only
HTTPS artwork on `media.newyorker.com` is downloaded; other artwork uses a bundled
fallback. Provider identifiers are generated hashes and support read access only.

Add a bounded artwork cache and expose downloaded covers and article images
through local `content://` URIs. Use bundled `android.resource://` artwork when
an image is absent or cannot be loaded. Android's car-media guidance calls for
these local URI schemes rather than remote artwork URLs.

Construct artwork URIs quickly, make them readable by the car host, and perform
downloads outside the service's main thread. The provider should expose only
cached artwork through validated identifiers. Bound image dimensions and storage.

Avoid embedding artwork bytes in every browse item, which can exceed Binder
transaction limits. Keep the existing phone and notification artwork behavior
working while adding the car-specific delivery path.

Reference: [Car media artwork](https://developer.android.com/training/cars/media/create-media-browser/media-artwork).

## 6. Verify controller access and run an early head-unit test

The service now accepts the app's UID, framework-trusted controllers, and Android
Auto whose installed UID and signing certificate match the published production
or rotated production identity. Private phone selection commands require the
app's UID. Automated policy tests pass; verify a real Android Auto host before
considering this step complete. Package-name recognition alone does not grant
access.

Use Google's Desktop Head Unit in this first batch. Confirm that the app appears,
the host connects, a small issue/article library is browsable, local artwork
loads, and one article plays through the service workflow with the phone activity
closed. Recheck basic phone playback after the service migration.

Do not wait until the full feature set is complete to discover a service,
controller-access, or legacy-interface compatibility problem.

References: [Desktop Head Unit testing](https://developer.android.com/training/cars/testing/dhu),
[Media3 1.8.0 session APIs](https://github.com/androidx/media/blob/1.8.0/libraries/session/src/main/java/androidx/media3/session/MediaSession.java).

## 7. Complete selection, cancellation, and shared playback behavior

Implemented: a service-owned coordinator cancels superseded callback futures
immediately and uses a request generation check before committing a resolved
selection. Phone commands and car media-ID requests share this coordinator.
Pause/Stop are intercepted at the player, including Pause before playback has
started, and service shutdown cancels pending requests. The previous track stays
active until a replacement is ready. Service-created reuse tags let the car keep
the existing source, position, and speed; explicit seeks still apply. Cancelled
phone requests return to displaying the shared player instead of a resolution
error. The service validates canonical article IDs and rejects a resolver response
for a different article.

JVM regressions cover noncooperative late responses, caller cancellation, shutdown,
failed replacement, and mismatched identities. Four device tests cover actual
player Pause/Stop and source reuse; they compile but have not been run here.

Handle incoming media IDs through the shared service workflow, resolve the
selected article through `POST /articles/audio`, and supply the playable
MediaItem to ExoPlayer. Validate the requested article identity; use the source
returned by the backend rather than accepting an arbitrary controller-supplied
streaming URL.

Specify that the latest selection wins across phone and car. Cancel superseded
resolution requests and discard late results even if cancellation does not stop
the upstream work immediately. Pause or Stop while resolution is pending must
prevent delayed playback. Ensure all callback futures complete or cancel and
that service shutdown cancels its work.

Failed selections must not replace the current track unexpectedly. Preserve the
existing track until a replacement is ready. Reuse an already active article
without resetting its position or speed.

Selecting a playable article in the car starts playback as an explicit user
action. Connecting to Android Auto or browsing issues will not start playback.
Opening a metadata page on the phone continues to require a separate Play action.

## 8. Restore and record progress in every playback path

Implemented: the 100-article progress cache now stores playback speed in schema
v2 and reads v1 entries without losing positions. The service player records the
outgoing track before every source replacement, clear, Pause, and Stop; explicit
seeks save even while idle. Periodic and event-based saves continue in the
background. Explicit positions win; unfinished positions and speed restore for
phone/car selections. Retries keep live position/speed, and a shortened recording
restarts at zero rather than its end. Completed progress entries are still removed.

Apply saved positions in the service/session callbacks for both phone and car
selections. Explicit positions take precedence over cached progress. Preserve
position and speed when replacing the source during a stream retry.

Save the previous track's final position before every replacement, including
car-originated changes. The existing phone-only pre-switch save must not be the
only mechanism. Continue service-side recording during background playback,
seeking, pause, and disconnection.

Preserve the bounded 100-article progress cache and migrate its existing data if
the schema changes. Completed articles restart at the beginning, and saved
positions beyond a newly shortened recording must not seek to its end.

## 9. Support playback resumption after a cold start

Implemented: a separate one-article `LastPlayedCache` persists canonical identity,
display metadata, duration, speed, and completion status, with streaming URLs
stripped. `onPlaybackResumption` resolves this identity only for an explicit Play
from a connected controller, preserving unfinished progress or restarting a
completed article at zero. Recent browser requests use a separate `ny:recent`
root with metadata/local artwork and completion hints, without audio resolution
or autoplay. Empty history returns a useful error. Cancellation remains linked
to the shared selection coordinator.

This batch supports cold Play through a connected phone/car media controller;
it does not register a boot/background media-button receiver or advertise an OS
resumption notification. If that receiver is added later, foreground-service
startup during asynchronous resolution must be handled. Media3 1.8.0's boot
metadata callback is already treated as metadata-only: it runs outside the
controller player-command context and does not cancel other requests.

Persist the last-played article ID and enough display metadata independently of
unfinished progress. The progress cache currently removes completed entries,
so it cannot alone identify the most recently played article.

Implement `onPlaybackResumption` for a Play request when the service has no
current item. Resolve the saved article, return its starting position, and
handle the car/browser's recent-content requests. A finished article can remain
the last-played item while having a zero resume position.

Starting the service or reconnecting a host must not autoplay. Resumption requires
an explicit Play or voice-play request. Cached metadata can be displayed without
network access; a streaming source still requires a usable network or an already
available session. No offline audio downloads are part of this version.

Reference: [Media3 1.8.0 playback resumption and starting-position APIs](https://github.com/androidx/media/blob/1.8.0/libraries/session/src/main/java/androidx/media3/session/MediaSession.java).

## 10. Expose playback controls and recover from failures

Implemented: session button preferences expose 15-second seek-back/seek-forward
controls; next/previous-track commands are withheld for single-article playback.
Nonfatal session errors provide local messages and account/subscription codes
while preserving the previous track. Confirmed missing narration updates catalog
availability and notifies browsers; unknown summary refreshes retain that known
answer, while explicit positive summaries can restore playability. Automatic source recovery is limited to one
fresh-URL attempt per selection for HTTP 401/403/404/410 while playback is wanted;
a manually refreshed selection consumes that budget. Other failures need an
explicit retry. Phone Retry and car Play on a failed source both resolve with
`refresh: true`, preserve position/speed, and respect Pause/Stop or superseding
selections. Exact control placement still requires host validation.

Support play/pause, seeking, and 15-second backward/forward actions through the
session. Populate title, author, artwork, duration, and playback position.

Start with single-article playback. An issue queue and automatic next-article
playback can be a separate addition. Test the controls each host displays;
custom speed actions and exact button placement are not guaranteed by the phone
UI. Keep seeking distinct from next/previous-track commands.

Translate network failures, backend session problems, unavailable narration, and
stream failures into media-library/session errors the car can display.

Use the explicit refresh operation from step 1 for failed-stream retries. Bound
retry attempts and preserve the user's latest playback intent. A confirmed
`audio: null` response should update the item's known availability and notify
browsers, rather than repeatedly offering a track known to have no narration.

Reference: [Playback controls](https://developer.android.com/training/cars/media/enable-playback).

## 11. Add Continue Listening

Implemented: the local root now includes Continue Listening. Progress schema v3
stores sanitized article metadata alongside positions and speed, retaining up to
100 unfinished articles in most-recently-updated order. v1/v2 records retain their
positions and speed; metadata hydration preserves that order. Completed articles
and confirmed narration absence are excluded. Stored audio source URLs are removed.
Offline restarts can list saved metadata, independently of last-played resumption.

Extend the local metadata store with article IDs, titles, authors, artwork
references, and recency information. Expose enumeration of unfinished articles
and combine it with their saved progress. Keep this bounded and preserve existing
progress during migration.

Continue Listening should work after an app restart without first browsing every
issue. Render cached metadata, resolve only the article selected for playback,
and update the list as progress changes or articles finish. Keep last-played
resumption separate from this unfinished-article list.

## 12. Support voice playback and define search coverage

Implemented: title/author matching normalizes accents, case, and punctuation.
Exact unique titles win; ambiguous queries request a more specific title, and no
matches/network failures report errors. Empty voice requests choose the latest
unfinished article, then last played. The service warms the newest five issues
independently of the phone and combines them with Continue Listening.
Native search callbacks, legacy request-metadata voice play, and activity voice
intents use the shared service. Search snapshots are separated by caller, bounded
to 32 queries for five minutes, and cleared on disconnect. Requests resolve only
the selected article's source. Legacy searches receive completion notifications
even when their result is empty or failed.

Add title and author matching, search results, and a sensible empty-query
behavior—such as resuming the most recent unfinished article.

Implemented first-version scope: the five newest issues plus Continue Listening.
Warm this searchable metadata index independently of phone browsing, use the
persistent cache on a cold start, and load missing recent metadata asynchronously.
Voice results should not depend on whether the user previously opened those
issues on the phone. Full-archive search would benefit from a backend search
endpoint and is a separate expansion.

Implement `onSearch`, `onGetSearchResult`, and voice-play handling in the shared
selection callback. Include legacy play-from-search requests delivered through
MediaItem request metadata. Handle empty queries, multiple matches, no matches,
and network failures explicitly. An empty query can resume the most recent
unfinished article or fall back to the last-played item. If neither exists,
report the absence instead of inventing a playable result.

Voice search belongs in the release scope: Android's car-media guidance requires
it.

References: [Voice actions](https://developer.android.com/training/cars/media/voice-actions),
[search support](https://developer.android.com/training/cars/media/create-media-browser/browsable-search).

## 13. Verify lifecycle behavior and phone/car coordination

Implementation and automated coverage are complete; live verification is pending.
Backgrounding the phone retains its binding until a pending explicit request
settles, suppresses callbacks to hidden screens, and releases the controller
without stopping the shared player. Request ownership prevents disposal of an
old phone page from canceling a newer car selection. Active player navigation
follows a car-selected article on return while preserving deliberate browsing.
Service destruction cancels outstanding selection and browse futures.
JVM tests cover these decisions and restart persistence. Device smoke tests add
search-error completion and multiple-controller reconnects; they compile but have
not been executed. Follow the step 13 checklist in the test guide for locking,
rotation, process death, and actual phone/DHU coordination.

Test starting from the car with the phone app closed, switching between phone
and car controls, disconnecting/reconnecting, phone locking, and service
recreation. Browsing and playback must work independently of Compose screens.

Include process death, restored navigation, a cold Play request, and concurrent
phone/car selection. Verify that cached artwork and recent metadata are readable
by the host after a restart. Cover unavailable networks and renewed backend
subscriber sessions, without requiring account interaction on the car screen.

## 14. Complete automated, head-unit, and real-car validation

Add automated tests for the browsing hierarchy, pagination, media IDs,
selection, explicit versus cached start positions, progress migration, completed
articles, fresh-URL retries, cancellation, and errors. Verify that browsing does
not trigger audio resolution, library roots return promptly, and large browse
responses do not carry embedded artwork.

Use Google's Desktop Head Unit to test discovery, browsing, playback, voice
requests, and reconnects. Finish with a real head unit and regress the phone
app's existing behavior.

Reference: [Desktop Head Unit testing](https://developer.android.com/training/cars/testing/dhu).

## 15. Prepare distribution

Build the release bundle, configure Android Auto distribution in Play Console,
and check the applicable car-app quality requirements before public release.

Reference: [Car app distribution](https://developer.android.com/training/cars/distribute).

## Proposed review and implementation batches

| Batch | Steps | Result |
| --- | --- | --- |
| 1 | 1–6 | Shared service foundation, fresh-URL retry support, discovery, fast metadata browsing, local artwork, and an early Desktop Head Unit test |
| 2 | 7–10 | Coordinated playback requests, cancellation, progress restoration, cold-start resumption, controls, and recovery |
| 3 | 11–15 | Continue Listening, defined voice-search coverage, lifecycle regression tests, full head-unit/car validation, and release preparation |

## Review priorities

- Move resolution and starting-position decisions into the service so car
  requests cannot bypass the phone's existing behavior.
- Add local artwork delivery as a dedicated implementation step.
- Implement the backend's refresh flag before relying on stream retries.
- Keep root/item metadata lookup separate from playback resolution, with an
  immediate root response and bounded catalog caching.
- Persist last-played metadata separately from unfinished progress and preserve
  existing cache data during migration.
- Verify real controller access and a minimal head-unit flow in the first batch.
- Define voice-search scope and the behavior of overlapping Play, Pause, Stop,
  and selection requests before building the full car experience.
