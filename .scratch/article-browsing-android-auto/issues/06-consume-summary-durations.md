# Ticket 06 — Show backend narration durations before first playback

Status: resolved
Type: task
Blocked by: 02

Part of [the feature spec](../spec.md), delivery step 6 and FR-001. Backend
implementation and duration coverage belong in `audio_player_be`. The app
can implement and test against this contract before the backend deploys it.
See the [backend handoff](../backend-duration-handoff.md) for coverage goals and
the investigation into how to populate durations.

## API contract

- `GET /issues/{issue_date}/articles` adds `duration_ms` to each article object
  in `articles`. It is an optional JSON integer in milliseconds. When present
  with a value, it is the positive duration of the article's narration.
- The backend omits the field or returns `null` when the duration is unknown,
  including articles without narration. `audio_available: false` must never
  accompany a positive duration.
- The change is additive and keeps the existing response version. The field
  contains metadata only. The app continues to resolve a stream on demand with
  `POST /articles/audio`.

## Android work and acceptance

- Add a nullable `Long` with a default of `null` to `ArticleSummaryDto`, mapped
  from `duration_ms`. The current DTO omits the field and the JSON decoder
  ignores it if the backend sends it today.
- Map positive values to `AudioInfo.durationMs` without a stream URL. A fresh
  issue response with a known duration shows the existing label on the phone
  and in Android Auto before first playback, without resolving audio on browse.
- Missing, null, zero, and negative durations preserve any valid cached
  duration. With no cached value, omit the label. An explicit
  `audio_available: false` clears narration metadata and its label.
- Verify the new and legacy response shapes with fixtures. Verify deployed
  behavior once the backend serves the field. Phone, Desktop Head Unit, and
  real-car acceptance remain in delivery step 7.

## Answer

Implemented on 2026-11-12. `ArticleSummaryDto` now decodes optional nullable
`duration_ms` as a `Long`. Positive values become source-free narration metadata
and flow through the existing phone rows and car catalog before first playback.
The existing cache merge preserves valid durations for missing, null, zero, and
negative values; explicit `audio_available: false` clears narration metadata.

Four new JVM tests cover the new and legacy response shapes, fresh article rows
and car browse subtitles without audio resolution, cache persistence/restoration,
the missing/null/nonpositive matrix with and without cached durations, replacement
of learned durations, explicit absence, short recordings, and values above the
integer range. All 189 JVM tests pass. Debug APK assembly, Android test source
compilation, Android lint, and `git diff --check` pass. Gradle checks used
`-PbackendBaseUrl=https://example.com/`; they did not exercise a live device.

Separately, a read-only request to the configured deployed backend's
`GET /issues/2026-11-16/articles` confirmed version 1 and positive durations for
all 14 narrated articles among 24 total. The 8 explicitly unnarrated and 2
unknown-availability articles returned null durations. No audio-resolution
requests were made. See the [backend handoff](../backend-duration-handoff.md)
for the observed delivery and limits of this coverage check.

Phone, DHU, and real-car presentation remain delivery step 7 acceptance; this
ticket does not claim those host checks or historical backend backfill coverage.

## Comments

### 2026-11-12 — Article page and Android Auto duration follow-up

The user reported missing durations on the phone article page and Android Auto
article list. A read-only package check found that the connected phone's app was
last updated on November 10, before this ticket's summary-duration implementation.
The article page already renders known duration in `NarrationMetadata` and the
playback time labels; Android Auto article rows include it in browse subtitles.

Added `SummaryDurationScreensTest` to exercise both presentation paths from an
HTTP summary fixture before playback, including the article page's `10 min ·
Narrated` and `10:57` labels and the car row's `10 min · Article description`
subtitle. Both checks assert browsing makes only GET requests and retains no
stream source. The debug app and instrumentation APK build successfully using
the configured backend. The device-test approval request was cancelled; the user
will build/install the app. These new instrumentation tests compiled but were
not run, and no device or Android Auto host acceptance is claimed.

### 2026-11-12 — User feedback and pull request

After opting to build the app locally, the user reported "looks great" and
requested a branch and PR. Ticket 06 covers the summary-duration implementation
and its article-page/Android Auto presentation checks. This records the user's
feedback; automated instrumentation execution and the complete step 7 host
acceptance matrix remain unverified.
