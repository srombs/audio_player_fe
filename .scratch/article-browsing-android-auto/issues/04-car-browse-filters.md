# Ticket 04 — Android Auto browse filters and article-list progress

Status: claimed
Type: task
Blocked by: 03

Part of [the feature spec](../spec.md), delivery step 4, FR-004 and the
article-list placement of FR-002. Ticket 03 is resolved and merged into `main`.
Branch: `ticket-04-car-browse-filters`, based on `main` (`6078c24`).

## Problem

Android Auto currently opens an issue directly into its eligible article list.
Listeners cannot choose All, Unlistened, or Listened as they can on the phone.
Ticket 03 supplies a shared issue summary on magazine entries, but the article
list still needs a parent title carrying that progress.

## Scope

Use the existing media tree to expose:

`Issues → selected issue → All / Unlistened / Listened → articles`

Put the shared whole-issue summary in each filter folder's display title, for
example `Unlistened · 3/12 listened`, so the host can use it as the article-list
heading. Reuse ticket 03's summary, membership persistence, and invalidation
rules, plus the phone's `ArticleListenFilter` and `filterListenedArticles`.

## Acceptance criteria

### Browse tree and identity

- Opening an issue returns exactly three browsable, nonplayable folders, ordered
  All, Unlistened, Listened. Opening a folder returns its article rows. Folder
  navigation does not select audio or interrupt playback.
- Folder IDs deterministically encode the issue and filter. Existing issue IDs
  and canonical playable article IDs remain stable across navigation and restart.
- Folder lookup returns metadata consistent with the folder listed under its
  issue. Reject malformed dates, unknown filters, and invalid folder IDs through
  the existing library error path; never treat them as article URLs or resolve
  them as audio.

### Filtering and presentation

- Exclude known unnarrated articles in every view; unknown narration remains
  eligible. All returns the eligible browse set, Unlistened excludes IDs in
  durable listening history, and Listened includes only IDs in that history.
  Partly played articles remain Unlistened until the existing completion rule
  qualifies them. Replaying an article retains its listened status.
- Filter before display pagination and preserve source article order and
  identity. Paging has no skips or duplicates for an unchanged catalog/history
  snapshot. Preserve existing page validation, bounded replies, and overflow
  protection, including legacy unpaged requests.
- Every folder displays the same whole-issue summary as the magazine entry,
  independent of the selected filter or page. A confirmed empty eligible issue
  displays `0/0 listened`; unavailable membership produces no invented fraction
  and exposes `Progress unavailable` in folder metadata where space permits.
- Preserve article titles, authors, artwork, known-duration subtitles, Listened
  labels, and completion extras. Use the existing shared summary rather than
  counting the filtered rows. Whole-issue totals remain exact even when the
  existing 100-article detail cache bounds the browsable set.
- An empty filtered result is a successful empty list, distinct from an
  uncached metadata load failure, which uses the existing retryable error path.
  Retain stale cached browsing during an outage and rebuild folder selections
  from cached metadata/history after restart.
- Selecting a car folder does not change the persisted phone filter. Search,
  Continue Listening, recent items, and voice selection retain their behavior.

### Live updates and playback

- Completion on phone or car refreshes the magazine list, the affected issue's
  folder titles, and all three filter parents. Child-change counts describe each
  parent's actual children: three for an issue and the appropriate filtered
  article count for a filter folder, rather than the whole-issue denominator.
- Handle multiple changed listened IDs in one history emission and articles
  shared across issues. Replaying a completed article does not increase totals
  or return it to Unlistened.
- Membership refreshes and narration eligibility changes update affected
  summaries and filter results, including after detail eviction or when legacy
  membership is unavailable. Preserve ticket 03's protection against stale
  refreshes overwriting newer narration metadata.
- If completion removes the active article from Unlistened, its playback
  continues with the same identity, position, play/pause state, and speed.
- Browsing and folder lookup never resolve audio, expose signed stream URLs,
  or trigger an unbounded catalog fetch. Reuse existing deduplicated loads and
  bounded hydration.

## Implementation starting points

- `CatalogLibrary`: folder IDs, child/item lookup, filtering, shared summaries,
  and parent-specific change counts.
- `LibraryMediaItemMapper` and `PlaybackService`: folder presentation, service
  lookup, completion metadata, and child-change notifications.
- `ListeningHistoryCache`: existing filter enum/helper and durable listened IDs.
  Keep one source of completion truth; add no car filter preference store.

## Validation

Extend existing `CatalogLibraryTest` coverage for folder order/flags and lookup,
invalid IDs, all three results, filtering before pagination, empty versus error
results, restart/offline behavior, and consistent whole-issue summaries with
large or evicted article lists. Include mixed narration availability and
multiple/shared article completions, with per-parent notification counts.

Extend the existing mapper and `MediaLibrarySmokeTest` seams to check returned
folder metadata, stable playable IDs, service errors, live child notifications,
and uninterrupted playback when completion removes the active row. Assert
observable results and that browsing never resolves audio; introduce no new
production test facade or parallel playback architecture.

Run focused JVM tests during implementation, then the full JVM suite, main Kotlin
and instrumentation-source compilation, debug APK assembly, and Android lint.
Record connected instrumentation execution separately from source compilation.

During delivery step 7, verify all three paths, the extra navigation level,
folder/heading progress visibility, empty filters, live completion, reconnect,
and phone-background behavior on DHU/car. Record the tested versions and host
limitations in the existing Android Auto test guide. Automated service checks
alone do not establish host presentation acceptance.

## Out of scope

Changing completion thresholds, the phone filter UI/preferences, ticket 03's
counting population or cache limits, backend duration/aggregate contracts,
rewind controls, custom Android Auto screens, and a Media3 upgrade.

## Comments

- Created at the user's request from the canonical step 4 scope. Folder
  navigation and text placement follow the spec's proposed defaults; actual
  Android Auto presentation remains subject to integration acceptance.
