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

## Fog

- Ticket 04 still needs car filter folders and article-list summary placement.
- DHU/car fraction visibility and live refresh acceptance remain unverified.
- Immediate cold-install counts across every heading would need a backend contract.
