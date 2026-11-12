# Article browsing and Android Auto

## Notes

The [canonical spec](spec.md) defines delivery order and acceptance gates.
Ticket 01's standalone latest-build verification was skipped at the user's request.
[Ticket 06](issues/06-consume-summary-durations.md) consumes the duration API
contract. Backend population and historical coverage belong in `audio_player_be`;
the deployed November 16 issue supplies durations for all 14 narrated articles.

## Decisions-so-far

- [Ticket 02](issues/02-show-known-article-durations.md) retains known durations
  and includes them in car browse descriptions; ticket 06 adds summary durations
  before first playback.
- [Ticket 03](issues/03-shared-issue-progress.md) implements shared issue progress
  from complete distinct membership and durable listened history. Membership for
  200 headings survives the 24-list article cache. Unknown membership stays
  unavailable; recent/requested metadata hydrates in bounded batches.
- [Ticket 04](issues/04-car-browse-filters.md) adds All / Unlistened / Listened
  folders with whole-issue summary titles, filtering before pagination, and
  per-parent live counts. All 185 JVM tests and build/lint checks pass; both
  independent review axes found no issues. Device/host acceptance is pending.
- [Ticket 05](issues/05-android-auto-rewind.md) updates Media3 to 1.9.1 so Android
  Auto receives the standard 15-second rewind button. The user confirmed the fix
  in DHU.
- [Ticket 06](issues/06-consume-summary-durations.md) consumes optional positive
  summary durations before first playback, preserving cached values when missing
  or invalid and clearing narration on explicit absence. All 189 JVM tests,
  debug APK assembly, Android test source compilation, and lint pass. The deployed
  November 16 issue returns durations for all 14 narrated articles; phone/DHU/car
  presentation remains step 7 acceptance.
  The user subsequently reported "looks great" after opting to build locally;
  the complete host acceptance matrix and instrumentation execution remain pending.
- [Ticket 07](issues/07-car-category-counts.md) replaces ticket 04's repeated
  shared-count titles with category-only titles and one subtitle count. All and
  Listened show listened/total; Unlistened shows remaining/total unlistened.
  All 190 JVM tests and the connected real-MediaBrowser smoke test pass. Updated
  DHU/car presentation remains step 7 acceptance.

## Fog

- Ticket 04's folder navigation, heading visibility, and uninterrupted playback
  during live completion still need DHU/car acceptance.
- DHU/car fraction visibility and live refresh acceptance remain unverified.
- Ticket 07's category-only titles and category-specific subtitles need visual
  DHU/car acceptance; automated MediaBrowser metadata and completion refresh pass.
- The rewind edge cases and real-car check remain in step 7 host acceptance.
- Summary duration presentation before first playback still needs phone/DHU/car
  acceptance. Historical backend duration coverage was not audited here.
- Immediate cold-install counts across every heading would need a backend contract.
