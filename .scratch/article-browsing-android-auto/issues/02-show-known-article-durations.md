# Ticket 02 — Show known article durations in phone and Android Auto lists

Status: resolved
Type: task

Part of [the feature spec](../spec.md), delivery step 2, FR-001.
Ticket 01 was latest-build verification, skipped at the user's request.
Branch: `ticket-02-known-article-durations`, based on the latest `main`.

## Scope

Preserve known narration duration and availability through repository and car
catalog refreshes, and include known duration in Android Auto browse subtitles.
Reuse the phone's existing duration label. Apply the same browse metadata to
issue articles, search results, Continue Listening, and recent items.

## Acceptance criteria

- Refreshed phone article lists receive cached narration duration and confirmed
  availability; metadata persists across restart without signed stream URLs.
- Car issue refreshes keep duration when an incoming summary omits it, including
  explicitly narrated summaries. Confirmed unnarrated results clear audio.
- Car subtitles put known duration before the description, following any
  Listened prefix; use whole minutes with a minimum of one minute.
- Unknown/nonpositive duration produces no duration label. Titles, authors,
  playback IDs, and completion indicators retain their existing meaning.
- Browsing does not resolve audio. Existing repository/catalog and presentation
  tests cover these behaviors; Android-dependent changes compile.

## Deferred work

Backend duration metadata before first playback is delivery step 6. This ticket
only displays already-known durations and does not complete all of FR-001.

## Answer

Implemented shared narration-metadata merging and returned merged repository
results to phone article lists. Car catalog refreshes preserve learned duration
and availability. Issue, search, Continue Listening, and recent browse rows carry
known duration, displayed before descriptions and after any Listened prefix.

Validation: all 169 JVM tests passed, Android instrumentation sources compiled,
the debug APK built, and Android lint passed. Checks used an example backend URL
for build configuration; no deployed backend or host was tested. Mapper device
tests were compiled but not executed. Phone/DHU/car display validation remains
part of later integration acceptance, rather than a standalone prerequisite.

FR-001 remains partially delivered until backend summary metadata can supply
durations before first playback. No backend change is included in this ticket.

## Review artifacts

- [HTML overview with interactive before/after mockups](../../../docs/reviews/ticket-02/index.html).
- [Phone mockup PNG](../../../docs/reviews/ticket-02/screenshots/phone-after.png).
- [Android Auto mockup PNG](../../../docs/reviews/ticket-02/screenshots/car-after.png).

The images are illustrative browser-rendered mockups using fictional content,
not device screenshots or evidence of actual Android Auto host presentation.
