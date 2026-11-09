# Ticket 03 — Shared issue summaries, persistence, and updates

Status: resolved
Type: task
Blocked by: 02

Part of [the feature spec](../spec.md), delivery step 3, FR-002/FR-003.
Branch: `ticket-03-shared-issue-progress`, based on ticket 02 (`4978754`).
The user confirmed the scope and counting defaults before implementation.

## Scope

Derive one shared listening summary for each issue from complete article
membership and the existing durable listening history. Expose the summary in
catalog nodes and Android Auto magazine display titles, retaining issue identity
and the underlying title. Prepare the shared summary for ticket 04's folders.

## Acceptance criteria

- Count distinct canonical article IDs whose narration is not explicitly false.
  Unknown narration counts provisionally. Count listened IDs only within that
  eligible set, independently of display pagination and article-cache limits.
- Display `3/12 listened`. A loaded empty eligible set is `0/0 listened`;
  unloaded membership has no numeric fraction and reports unavailable progress.
- Persist membership separately from the 24 full article lists, retaining it
  for up to 200 catalog issues. Totals survive restart, offline browsing, full
  article-list eviction, and responses exceeding 100 articles.
- Older snapshots do not invent exact totals from possibly capped lists.
  Invalid membership is discarded and rebuilt from metadata without clearing
  independent listening history or valid catalog data.
- Return cached summaries immediately. Hydrate recent/requested issue pages in
  bounded background batches using existing deduplicated loads, prioritizing
  the five-issue warm-up and explicitly opened issues. Root browsing stays local.
- Completion updates, including multiple changed IDs per history emission,
  refresh the issues list and affected issue parents. Replay does not increase
  the numerator. Narration and membership changes update every affected issue,
  including issues whose full article list was evicted.
- Browsing does not resolve audio or persist signed stream URLs. Preserve the
  existing completion threshold, playback behavior, title, artwork, and IDs.

## Validation

Extend the agreed catalog/persistence and media-service/presentation test seams.
Run focused JVM tests during development, then the full JVM suite, Kotlin and
instrumentation-source compilation, debug APK assembly, and Android lint.
Record actual device/DHU/car checks separately from compiled device tests.
Create a self-contained HTML overview after implementation.

## Deferred work

Ticket 04 adds All/Unlistened/Listened folders and article-list summary placement.
Cold-install counts for all 200 headings require a follow-up backend contract
if immediate complete coverage is desired. Host display acceptance is step 7.

## Comments

- User approved creation and implementation of this ticket, followed by an HTML
  overview. Existing test boundaries and the proposed counting rules are agreed.

## Answer

Implemented one shared issue summary from complete, distinct membership and the
existing listening history. Magazine browse metadata includes the fraction in
both title fields and the subtitle, while the underlying issue title and ID stay
unchanged. Unknown membership reports unavailable progress; empty eligibility
reports `0/0 listened`.

Catalog v2 persists compact membership independently of full article lists.
Membership is retained for all 200 headings, survives detail eviction and
restart, and can be rebuilt independently when absent or corrupt. Recent issues
and up to ten rows of a requested page hydrate in background batches of two,
sharing in-flight loads with explicit browsing and the existing five-issue warm-up.
Completion snapshots diff every changed ID, and narration updates reach issues
whose article details were evicted. Newer narration resolution wins over an
already-running browse response. Browsing never resolves audio or stores signed
stream URLs.

Validation: all 176 JVM tests passed; main Kotlin and Android test sources
compiled; debug APK assembly and Android lint passed (warnings remain in
unchanged files). The new mapper device case compiled but was not executed.
ADB found no connected device; phone/DHU/car presentation and connected
instrumentation execution remain integration acceptance work. Checks used an
example backend URL, without a live backend request.
The HTML overview passed headless-browser checks for all controls and scenarios,
local links, five screen widths (320–1440 px), and no external requests or
JavaScript errors. Desktop and mobile renders were inspected.

## Review artifacts

- [Self-contained HTML overview](../../../docs/reviews/ticket-03/index.html),
  with interactive before/after examples, completion/replay/eligibility changes,
  empty/unavailable/large-issue states, scope, and validation results.

The overview uses fictional data and browser-drawn mockups, not device captures.
Its interactive examples illustrate behavior; they do not run the Kotlin code.
