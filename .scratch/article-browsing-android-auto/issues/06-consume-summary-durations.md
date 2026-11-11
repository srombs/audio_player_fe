# Ticket 06 — Show backend narration durations before first playback

Status: ready-for-agent
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
