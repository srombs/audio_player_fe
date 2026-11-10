# New Yorker Audio — Android

Native Kotlin and Jetpack Compose app for the separate FastAPI backend at
`../audio_player_be`.

The car integration and remaining steps are documented in
[Android Auto implementation plan](ANDROID_AUTO_IMPLEMENTATION_PLAN.md).

Review ticket 02 in the [HTML overview with phone and Android Auto mockups](docs/reviews/ticket-02/index.html).
Open the HTML file directly in a browser; it works offline. The mockups illustrate
the implemented duration behavior and are not captured device screenshots.

## Implemented: browsing, playback, and networking steps 1–6

- Single `app` module with a Gradle wrapper and a dependency version catalog.
- Kotlin, Compose/Material 3, Navigation Compose, ViewModels, Coroutines, StateFlow.
- Retrofit/OkHttp networking with kotlinx.serialization; Coil artwork and Media3 playback.
- Domain models and a replaceable `NewYorkerRepository` contract.
- Real issues and article summaries loaded from the Railway backend.
- Two-column issue grid with remote covers and bundled artwork fallbacks.
- Article lists with thumbnails, section, title, author, and narration hints.
- Metadata-only article pages; pressing Play resolves audio through the service,
  with duration available after resolution. Fake data remains available for previews and tests.
- Player page with artwork, metadata, play/pause, seek bar, elapsed/total time,
  15-second skips, and playback speeds from 0.75× through 2×.
- Media3 ExoPlayer owned by a MediaLibraryService, with background playback,
  media notification/lock-screen controls, audio focus, and headphone-unplug pause.
- Shared playback state across destinations; opening an article does not autoplay
  or reset the current narration. Notification taps return to the current player.
- Buffering, connection failure, and stream failure states with retry controls.
- Type-safe routes carry IDs only. Each destination reloads its own data through
  a ViewModel and collects state lifecycle-aware.
- Known narrated and unknown articles can open the player page. Known unnarrated
  articles cannot; resolution can confirm that an unknown article has no narration.
- Normal back navigation; state restoration uses Navigation Compose's saved IDs.
- Explicit loading, content, empty, and error states with retry/refresh actions.
- Specific connection, timeout, backend subscriber-session, subscription, library,
  and audio-resolution messages. Loaded issue and article screens offer Refresh.
- Refresh cancels the previous request and prevents stale results from overwriting
  newer data. Cancellation does not display an error.
- Empty article lists keep their issue header. An unnarrated player route displays
  a narration-unavailable message while retaining the article metadata.
- State messages announce updates to accessibility services and support scrolling
  when text is enlarged.

Networking steps 4–6 are implemented. The app now selects
`NetworkNewYorkerRepository` and uses the backend URL supplied at build time.
See the configuration instructions below.

Open an issue, select an article, and press Play to check narration and start audio. Opening an article never autoplays. Unknown narration
hints remain selectable; an `audio: null` response retains article
metadata, shows the narration-unavailable message, and disables play, seek, skips,
and speed controls. Known unavailable narration shows this immediately without
an audio-resolution request; a newly confirmed absence updates the open player.

The backend owns the subscriber session; the Android app needs no New Yorker
cookies or login. Browsing and resolving audio require network access and the
backend's working subscriber session. If the service needs to reconnect or renew
its subscription, the app explains the condition and provides Retry; it cannot
repair the server's session itself.

The service owns playback independently of the activity. Leaving the screen or
backgrounding the app releases the UI controller while the service can continue
playing. Returning reconnects to the current track, position, and speed. The
current MediaItem carries article metadata so returning through the notification
can render an active article without another backend request or an in-memory list
cache. If no active track exists, a restored route loads its saved article summary.
The bounded metadata cache retains 200 articles. Completed articles also retain
metadata in local history, so an older history entry can open after that cache
evicts it. Other evicted or previously unseen articles must be opened from their
issue again. A local progress cache restores the selected article’s position
when the user presses Play after a restart. Downloads remain future work.

Playback within 60 seconds or 5% of the end (whichever is smaller) saves a local completion-history record keyed by stable article
URL, containing metadata and the latest listened timestamp. The phone article list
shows a Listened label and All / Unlistened / Listened filters, updated immediately
when phone or Android Auto playback reaches that threshold. History in the home toolbar opens
an offline list of listened articles, most recent first, with listened dates
and links to the player. That action remains available during a catalog failure.
Replaying an article retains its listened status; finishing again updates its
timestamp without adding a duplicate. Earlier partial playback remains unlistened.
Unknown duration requires an actual ended event. Near-end listened status preserves
the saved position and Continue Listening entry until playback actually finishes.
The rule uses position, so seeking into the final threshold also counts. The
service checks playback events and its five-second progress timer.

Android Auto receives fully-played indicators in issue lists, Continue Listening,
search results, and recent items from the same local store. Its host controls
indicator presentation. The phone's All / Unlistened / Listened choice is shared
across all issues and persisted locally across app restarts.
Car browse subtitles include known narration duration before the description,
with “Listened ·” first for completed articles (for example,
“Listened · 27 min · Description”). Durations use whole minutes with a minimum
of one minute. Unknown durations are omitted. Issue articles, search results,
Continue Listening, and recent items carry this metadata without resolving audio
while browsing. Phone descriptions keep their original text alongside the
separate Listened label and existing narration-duration label.
The history keeps one record per completed article and has no entry-count eviction;
source URLs are excluded. It survives normal app updates/restarts, but clearing
app data or uninstalling removes it. No backend changes or cross-device sync are
used. Completion tracking starts with this version; previously discarded finish
records cannot be recovered. Manual listened/unlistened actions are not implemented.

Retrying a stream failure resolves a fresh source and resumes at the previous
position and speed. A connection retry reconnects the session and submits selection to the service.
Resolution retries cancel previous requests; leaving the player prevents a late
retry response from starting playback. Metadata errors reload the page on Retry. Resolution errors retry service-owned
selection without discarding the current track.

Progress is saved locally for the 100 most recently updated articles, keyed by
stable article URL rather than the potentially changing MP3 URL. The service saves
at five-second intervals and on playback events (including pause, seeking, and
completion). Switching tracks, releasing the UI controller, and service shutdown
also save progress. SharedPreferences persists the bounded JSON cache; it stores
position and duration, without caching audio files or resolved stream URLs.

Opening an article displays its saved elapsed time but does not autoplay. Play
starts from that position. The current active session always takes precedence over
a saved position. Completed narrations, or saved positions beyond a newly shortened
recording, start from the beginning. An abrupt process kill can lose the most recent
few seconds; clearing app data removes the cache. Playback speed is preserved for
an active session but is not persisted across restarts.

Refreshing an issue retains previously resolved narration durations in the metadata
cache and returns the merged metadata to phone article rows and car browsing.
Car catalog refreshes also retain duration when an explicitly narrated summary
omits it; unknown availability preserves confirmed narration facts, while a
confirmed absence clears narration metadata. Returning to a saved article
therefore displays both its elapsed time and the matching seek-bar position
before playback resumes, without resolving audio. Durations for articles that
have never been resolved still depend on future backend summary metadata.

## Android Auto foundation: steps 1–6

The playback service now exposes `MediaLibrarySession`. Phone selection commands
and car `onSetMediaItems` requests share service-owned resolution and starting
position logic. Explicit positions take precedence; otherwise the same active
article keeps its position/speed, or a different article restores saved progress.
The service saves the old track before replacement and does not accept incoming
stream URLs as playback sources. Phone requests send only article identity and
refresh intent. Private selection commands are restricted to the application's UID.

The manifest declares Android Auto media support and both Media3 library and
platform browser discovery interfaces. The library now exposes Issues → issue
heading → All / Unlistened / Listened → article, plus Continue Listening. Its local root is immediate; children and item lookup use
metadata without resolving audio. Known unnarrated entries are excluded and
unknown narration remains selectable.

Filter folder titles carry the shared whole-issue progress, such as
`Unlistened · 3/12 listened`. Filtering precedes pagination and uses durable
listening history without changing the phone's saved filter. Completion updates
the folder titles and each filtered list while the current article keeps playing.
The existing 100-article browse cap applies; the summary counts complete issue
membership. Folder/heading visibility and active-playback behavior still need
DHU/car acceptance.

The Issues folder requests a grid of magazine cover tiles in Android Auto.
Article entries and Continue Listening request list presentation. These are host
layout hints; Android Auto controls tile sizing and column count. DHU layout
verification remains pending.

Article rows and the player screen show the full backend description below the
title on the phone. Android Auto receives that description as its display subtitle for
browse results and playback metadata; the host controls truncation. Authors remain
in metadata for voice search. Descriptions are retained in the metadata/progress
caches, and absent descriptions omit the secondary text.

The car catalog persists up to 200 issue headings and 24 issue article lists,
with up to 100 articles per issue. Its five-minute freshness policy returns cached
metadata while refreshing stale data, shares overlapping car requests, and notifies
subscribed browsers when content changes. Cached content remains browsable during
outages; a cold load failure remains distinct from a successful empty result.

Car and session artwork uses local read-only content URIs. Downloaded New Yorker
CDN images are normalized to PNG at up to 512 pixels, with a 128-image / 32 MiB
cache, a 5 MiB download limit, and three simultaneous downloads. Bundled PNG
fallback artwork is served through the same provider. Browse items carry artwork
URIs without embedded bitmaps or audio sources. Phone artwork still uses its
existing image loader.

Controller access accepts the app's UID, framework-trusted clients, or Android
Auto with a matching installed UID and published production signing certificate.
Only the app's UID gets private phone selection commands. The user confirmed
Android Auto host connection and playback in the DHU; newer features need a
device regression run.

Continue Listening persists up to 100 unfinished articles with metadata,
position, and speed, preserving old progress during v1/v2 migration to v3. Voice
search covers the five newest issues plus Continue Listening, independently of
phone browsing. Title/author queries support accents and punctuation; ambiguous
matches ask for a specific title. Empty voice requests resume the newest
unfinished article or last played. Search never resolves an audio URL until Play.
Phone backgrounding and controller release preserve pending explicit playback;
request ownership prevents an old phone screen from canceling a car selection.
Steps 11–12 and step 13's fixes/automated coverage are implemented; step 13's live
validation remains pending.

See [Android Auto test guide](ANDROID_AUTO_TESTING.md) for device smoke tests,
DHU setup, and the live test checklist.

## Open and run

1. Open this folder in Android Studio.
2. Use JDK 17 and install Android SDK platform 36 plus Build Tools 35.0.0.
3. Configure your backend URL as described below, then allow Gradle sync to download dependencies.
4. Select the `app` configuration and an emulator or device running Android 8.0
   (API 26) or newer, then Run.

Android Studio manages `local.properties` with your SDK path; it is ignored.
Add your backend URL to that file for local builds:

```properties
backend.baseUrl=https://backend.example/
```

Replace the example with your own HTTPS backend URL, including its trailing slash.
For CI or a one-off build, inject it using an environment variable or Gradle property:

```sh
BACKEND_BASE_URL=https://backend.example/ ./gradlew :app:assembleDebug
./gradlew :app:assembleDebug -PbackendBaseUrl=https://backend.example/
```

The Gradle property takes precedence over the environment variable, which takes
precedence over `local.properties`. Missing or invalid configuration fails the
build with an explanation. Never put your personal URL in committed Gradle files.
The configured URL is included in the APK; enforce authentication and rate limits
on the backend rather than treating its address as a secret.

Command-line checks:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The second command requires a running emulator or connected Android device.
Repository tests cover issue isolation, ID lookup, missing IDs, unique IDs, and
unresolved narration metadata. Playback tests cover command routing, track
isolation, retry, seek bounds, time formatting, and synthetic test metadata. Device tests
cover playback controls, accessibility seeking, speed selection, media metadata
and notification artwork, plus forward/back navigation,
unnarrated articles, saved navigation restoration with a URL-based article ID, the two-column
grid, and article artwork/author/duration metadata.
ViewModel tests cover loading, empty results, errors, successful retry, ID lookup,
cancellation, and stale responses. Screen-state tests cover loading/error display,
retry and refresh callbacks, empty issue headers, and unnarrated player metadata.

For a visual review in Android Studio, open `ui/preview/ScreenPreviews.kt` in
Split or Design mode. It includes all three screen previews plus a narrow-screen
issue-grid preview with increased text size.
`ui/preview/StatePreviews.kt` adds loading, error, empty-library, and empty-issue
previews, including an error page with doubled text size on a small screen.

### Validation in the coding workspace

- Normal Gradle main compilation, all 178 JVM tests, and instrumentation-source
  compilation pass for the car library, selection, progress, resumption, and recovery changes.
- Debug APK assembly and Android lint pass. Resources and the merged manifest
  are processed successfully by Gradle against SDK 36.
- New device smoke tests cover real service root browsing without an activity,
  command availability, provider fallback/read-only access, invalid identifiers,
  and fixture image normalization. Their sources compile; they have not run.
- The user confirmed DHU connection, issue browsing, and audio playback. Steps 7–13
  and the remaining lifecycle checks need a device
  regression run. The coding sandbox cannot start ADB's socket/USB interfaces.
  The test guide records these limits and supplies the commands/checklist.
- The user confirmed live backend playback before the service migration. Phone
  playback, background behavior, notifications, and retries need a device regression
  check after installing the Android Auto changes.

Run the command-line checks above or build from Android Studio. On a device,
verify real issues and covers, article summaries, unknown narration selection,
audio resolution, confirmed absence, and resolution/session error retries. Check
Play/Pause, scrubbing, both 15-second skips, and speed changes. Then navigate
back, background the app, and lock the screen; check continued playback, media
controls, notification artwork, and returning to the current article. Check that
unplugging headphones pauses playback and an unavailable stream shows a retry.
For progress restoration, listen for at least ten seconds, pause, close the app,
reopen the same article, and press Play. Check the saved position, independent
positions for two articles, and restarting a completed narration from the beginning.

## Project layout

```text
app/src/main/java/com/rombs/newyorkeraudio/
├── MainActivity.kt
├── NewYorkerAudioApplication.kt
├── AppContainer.kt
├── data/network/       # Retrofit client, API, backend DTOs, and request failures
├── data/repository/    # Fake and network implementations, domain mappings
├── domain/model/
├── navigation/
├── playback/           # Service, controller, state, and media mapping
└── ui/
    ├── articles/
    ├── common/
    ├── magazines/
    ├── player/
    ├── preview/        # Android Studio screen previews
    └── theme/
```

`AppContainer` selects `NetworkNewYorkerRepository`. `BackendClient` and its
backend DTOs remain separate from domain models. The configured base URL is
`BuildConfig.BASE_URL`, generated from the build-time configuration above.
Its trailing slash supports Retrofit's relative endpoint paths. Test narration
metadata lives in `app/src/sharedTest/java`, shared by JVM and device tests, with
fictional metadata and an `audio.example` URL; it is excluded from the app.

The API client supports `GET /issues` (optional year),
`GET /issues/{issue_date}/articles`, `POST /articles/audio` with `url` and optional
`refresh: true` for failed-stream recovery, `GET /auth/status`, and `GET /health`. It uses 15-second connect/write,
60-second read, and 90-second total call timeouts. Retrofit suspend requests
cancel with their coroutine. Backend session status remains on the server.

The network repository maps issue dates to magazine IDs and canonical New Yorker
article URLs to app article IDs. The resolver's internal ID does not replace the
app ID. It persists bounded article metadata to retain artwork, section, author fallback,
and duration, while excluding expiring audio sources. Restored URL-based IDs can resolve without a
list cache, though artwork and section are unavailable until browsing reloads them.
Metadata lookup never calls the audio endpoint. Normal audio resolution uses the
backend cache; an explicit stream retry bypasses it. Stream URLs exist only in
the active playback item and response, never in the persistent metadata cache.
Issue and article list refreshes make new requests. Cached magazine metadata
avoids a duplicate issue-list request for a previously loaded issue header.

Known false narration hints map to no audio. True and unknown hints map to
unresolved `AudioInfo`, keeping unknown articles selectable for resolution. Only
the audio endpoint's result establishes a playable URL or confirmed absence.
`Article.audioAvailable` preserves the nullable hint independently of `AudioInfo`.
The article row labels and click behavior preserve this distinction.

HTTP failures retain status plus backend `detail.code` and `detail.action` when
available. Connection, timeout, and invalid-response failures are distinct;
cancellation propagates. Screen states map these to actionable messages without
showing raw server bodies. Subscriber-session recovery is performed on the backend.

No New Yorker cookies, credentials, storage state, or subscriber headers belong
in this app.

Build configuration references:
[AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom),
[ID-based type-safe navigation](https://developer.android.com/guide/navigation/design/type-safety),
[Media3 background playback](https://developer.android.com/media/media3/session/background-playback).

## Audio endpoint deployment check

If issues load but every article fails to resolve, confirm Railway runs the latest
backend image containing `POST /articles/audio`. The app distinguishes an
unstructured 404 from this endpoint from a structured missing-article response;
it displays “The audio service is unavailable” instead of saying the article
was removed. Upstream resolution failures use HTTP 502, and confirmed absence of
narration returns HTTP 200 with `audio: null`.

Set `BACKEND_BASE_URL` in your shell to your backend URL for this route-only check,
which requires no article resolution:

```sh
curl -i -X POST "${BACKEND_BASE_URL%/}/articles/audio" \
  -H 'Content-Type: application/json' -d '{}'
```

HTTP 422 confirms the route exists and rejected the intentionally missing URL;
HTTP 404 means that URL is not exposed by the deployed service. Rebuild/redeploy
the backend image, then retry an article in the app.
# audio_player_fe
# audio_player_fe
