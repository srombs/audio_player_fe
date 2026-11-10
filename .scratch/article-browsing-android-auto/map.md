# Article browsing and Android Auto

## Notes

The [canonical spec](spec.md) defines delivery order and acceptance gates.
Ticket 01's standalone latest-build verification was skipped at the user's request.

## Decisions-so-far

- [Ticket 02](issues/02-show-known-article-durations.md) retains known durations
  and includes them in car browse descriptions; backend duration coverage is later work.
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

## Fog

- Ticket 04's folder navigation, heading visibility, and uninterrupted playback
  during live completion still need DHU/car acceptance.
- DHU/car fraction visibility and live refresh acceptance remain unverified.
- The rewind edge cases and real-car check remain in step 7 host acceptance.
- Immediate cold-install counts across every heading would need a backend contract.
