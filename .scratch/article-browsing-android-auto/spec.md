# Article browsing and Android Auto implementation plan

Status: ready-for-agent

The user skipped ticket 01, the separate latest-build verification milestone.
[Ticket 02: known article durations](issues/02-show-known-article-durations.md)
is complete with automated checks. Host acceptance and
the remaining feature work are pending.

Source: [feature requests](../../FEATURE_REQUESTS.md), FR-001 through FR-005.
The testing approach was confirmed: reuse existing interfaces and test coverage.
Other product choices below are proposed defaults, not confirmed preferences.

## Problem Statement

Listeners want to choose articles by audio length, see how much of an issue they
have listened to, filter articles by listened status in Android Auto, and rewind
a short distance from the car's player.

The installed app may be older than the current source. Some requested behavior
already exists. The user chose to proceed with the known source gaps without a
separate latest-build verification ticket.

## Solution

Keep the existing phone and Android Auto playback architecture. Verify the latest
app, then fill the remaining gaps with consistent duration labels, issue-level
listened counts, and browsable car filters. Retain the existing 15-second rewind
unless testing shows that Android Auto does not expose or execute it correctly.

### Current source versus requested behavior

These findings describe the repository, not a verified installed build.

| Request | Current source | Remaining work after device verification |
| --- | --- | --- |
| FR-001: article duration on phone and car | Phone and car lists receive retained known duration after refresh. Car browse subtitles include duration before descriptions. Summary DTOs still have no duration field. | Verify host presentation during integration and add backend summary metadata for durations before first playback. |
| FR-002: issue progress inside car article browsing | Individual articles receive listened labels and completion extras. No issue-level listened/total summary exists. | Add shared issue counts and a host-supported place to display them. |
| FR-003: progress on car magazine entries | Magazine nodes have titles and covers, without listened/total counts. | Decorate issue entries with the same counts used inside the issue. |
| FR-004: car listened filter | Phone has persisted All / Unlistened / Listened filters. Car issue children only filter out known unnarrated articles. | Reuse the filtering rules through browsable car folders. |
| FR-005: short rewind on car player | Player configuration uses a 15-second seek-back interval and advertises a Back 15 seconds command button. | Verify visibility and behavior in the latest app; make a compatibility fix only if needed. |

### First milestone: verify the latest app

Skipped as a standalone ticket at the user's request. The checklist remains a
reference for later host acceptance; it does not block implementation.

Record the tested app version/commit and Android Auto version. Use an article
with known audio duration and one not yet resolved for playback.

1. On the phone, check duration labels before playback, after audio resolution,
   and after refreshing/reopening the issue.
2. On the phone, check the Listened label and All / Unlistened / Listened filters.
3. In Android Auto, check listened labels, article descriptions, and any existing
   duration display.
4. On the car player, look for Back 15 seconds in both the main controls and
   overflow. Check whether it rewinds within the current article.
5. Note any difference between the phone, Desktop Head Unit (DHU), and car.
6. Reclassify verified requests as complete, incomplete, or requiring a fix.
   Do not rebuild working features merely because they appear in the backlog.

## User Stories

1. As a listener, I want to distinguish existing functionality from missing features, so that implementation addresses the remaining gaps.
2. As a phone listener, I want to see an article's known audio duration in its row, so that I can choose something that fits my available time.
3. As a car listener, I want to see an article's known audio duration before selecting it, so that I can choose something that fits my drive.
4. As a listener, I want an unknown duration to remain unknown, so that I am not misled by a zero or estimated length.
5. As a listener, I want learned durations to survive refreshes and restarts, so that useful information stays available.
6. As a listener, I want browsing to use metadata without resolving every audio source, so that lists stay responsive.
7. As a car listener, I want listened and total article counts inside an issue, so that I can assess my progress while choosing an article.
8. As a car listener, I want the same fraction on the magazine entry, so that I can compare issues before opening them.
9. As a listener, I want both views to use the same counting rules, so that progress is consistent.
10. As a listener, I want partly played articles to remain unlistened until the existing completion rule is met, so that issue progress has a predictable meaning.
11. As a listener, I want replaying an article to retain its listened status, so that completed work does not disappear.
12. As a listener, I want duplicate article entries to count once per issue, so that progress is accurate.
13. As a listener, I want counts to reflect completion on either phone or car, so that switching devices within the app does not produce conflicting views.
14. As a listener, I want missing issue metadata to show an unknown state, so that an unloaded issue is not reported as empty.
15. As a listener, I want cached counts to remain usable offline, so that browsing still helps during an outage.
16. As a car listener, I want an Unlistened view, so that I can find articles I have left to hear.
17. As a car listener, I want a Listened view, so that I can replay articles I have finished.
18. As a car listener, I want an All view, so that I can return to the complete available list.
19. As a listener, I want filtering to preserve article order and identity, so that choosing an article still opens the correct narration.
20. As a listener, I want issue progress to describe the whole issue regardless of the selected filter, so that the denominator does not change unexpectedly.
21. As a car listener, I want an empty filter result to be distinguishable from a loading failure, so that I know whether there is anything to retry.
22. As a car listener, I want a short rewind control, so that I can hear a passage again without changing articles.
23. As a listener, I want rewind to stop at the beginning and preserve pause state and playback speed, so that it behaves predictably.
24. As a listener, I want phone and car playback controls to share saved progress, so that I can continue from the same position.

## Implementation Decisions

### Shared rules and proposed defaults

- Reuse `ListeningHistoryCache` as the source of listened status, keyed by
  canonical article URL. Do not derive completion from the bounded unfinished
  progress cache.
- Preserve the existing completion rule: an ended event, or remaining playback
  within the smaller of 60 seconds and 5% of known duration. Seeking into that
  range currently qualifies; this plan does not change that rule.
- Count distinct articles whose narration availability is not explicitly false,
  matching the car's current browse eligibility. Unknown narration remains
  selectable and counts provisionally; a later confirmed absence removes it
  from both numerator and denominator.
- The numerator is the intersection of eligible issue article IDs and listened
  history. An article appearing in two issues counts once in each issue.
- Use `3/12 listened` consistently. Counts always describe the unfiltered issue.
  A confirmed empty eligible set is `0/0 listened`; unloaded metadata has no
  numeric fraction and displays `Progress unavailable` where space permits.
- Use 15-second rewind to match the current phone/player configuration. A
  configurable interval and a separate 10-second button are outside this plan.

### FR-001: duration metadata and display

- Keep the phone's existing narration metadata component. Use body metadata on
  the phone and a short prefix in the car subtitle, before the article
  description. Example: `Listened · 24 min · Description`.
- Preserve article titles and authors. Keep completion extras alongside the
  textual label. Format positive durations consistently in whole minutes, with
  a minimum of one minute, matching current phone behavior.
- Omit duration when it is missing or nonpositive. Keep existing unknown and
  unavailable narration states; do not display `0 min` or estimate reading time.
- Populate duration on ordinary issue browse nodes as well as appropriate
  Continue Listening, search, and recent-item metadata. A missing duration in one
  presentation must not erase a newer known duration.
- Return merged repository metadata to callers so known durations reach refreshed
  phone and car lists. This propagation fix is implemented in ticket 02.
- Ensure car catalog refreshes preserve known duration even when the refreshed
  summary explicitly confirms narration but omits its duration. A confirmed
  absence of narration clears audio metadata.
- Deliver cached/known-duration improvements first. Full coverage before first
  playback depends on a backend metadata enhancement: propose optional
  `duration_ms` on article summaries, carrying a positive narration duration
  when known and otherwise null/absent. Keep older responses compatible.
- Verify backend support during that implementation task; the current frontend
  DTO alone does not establish what the deployed server supplies. The backend
  change and any metadata backfill are separate deliverables.
- Never call the audio resolver for every row or retain signed stream URLs in
  browse metadata. FR-001 is only partially delivered if previously unplayed
  articles still lack duration metadata.

### FR-002 and FR-003: shared issue progress

- Extend `CatalogLibrary` and its node presentation to expose optional issue
  listening summaries. Keep derivation shared between magazine entries and
  article/filter folders, rather than counting independently in each mapper.
- Derive membership from a complete issue metadata response before display
  pagination or the current 100-article cache limit. A truncated list must never
  be presented as an exact whole-issue total.
- Persist a compact issue-membership index separately from the 24 full article
  lists. Retain membership for up to the catalog's 200 issue headings so that
  evicting article descriptions does not erase progress for those issues.
  Membership contains canonical IDs and eligibility, not audio sources.
- Restore old catalog snapshots without inventing membership or zero counts.
  Recover corrupt derived data by rebuilding it from issue metadata; retain the
  independent listening-history store. If a future storage limit truncates
  membership, mark its summary unavailable rather than reporting an exact total.
- Render a short fraction in magazine display titles, because the requested car
  grid can omit subtitles. Keep the underlying magazine title unchanged. Put the
  fraction in filter-folder titles too, allowing the host to use it as the
  article-list heading. Verify actual visibility in DHU and the car.
- Return cached summaries immediately. Use existing deduplicated catalog loads
  to hydrate selected issues and recent/requested issue pages in bounded
  background batches; do not block the root or eagerly fetch 200 article lists.
  Issues not yet hydrated show unavailable progress until their metadata loads.
- Prioritize the existing five-issue warm-up and explicitly opened issues.
  If every magazine must show a count immediately on a cold install, record that
  as a follow-up backend aggregate/membership contract rather than adding an
  unbounded request burst.
- Completion, catalog membership changes, and narration resolution invalidate
  the issue list and every affected issue/filter folder. Recompute from a
  consistent history snapshot; do not only refresh individual article markers.
- Diff listened-ID sets when handling history updates so multiple completions
  between emissions are all reflected. Replaying an already-listened article
  may update recency without increasing the count.

### FR-004: browsable filters in Android Auto

- Reuse `ArticleListenFilter` and `filterListenedArticles`; introduce no new
  filtering rules or history store.
- Proposed browse path: Issues → selected issue → All / Unlistened / Listened
  folders → articles. Each filter folder includes the whole-issue fraction in
  its display title, for example `Unlistened · 3/12 listened`.
- Give filter folders deterministic IDs encoding issue identity and the filter.
  Keep issue IDs and playable article IDs stable. Extend folder parsing and item
  lookup so filtered parents cannot be mistaken for article URLs.
- Filter before pagination and preserve original article order. Never paginate
  first and then remove listened articles. Folder child counts and change
  notifications must reflect the particular filtered result.
- Use explicit car folder selection rather than changing the persisted phone
  filter. Opening a different folder is the car's selection state; no extra
  preference store is needed.
- Keep folders browsable and nonplayable. Opening them does not select audio or
  interrupt the current article. An empty filtered list is a successful empty
  result; a metadata load failure remains an error with retry.
- Keep the current article playing if completion removes it from Unlistened.
  Retain normal search and Continue Listening behavior independent of this filter.
- Validate the extra navigation level and heading visibility in DHU. This folder
  approach is a proposed use of the existing media tree, not a claim that the
  host offers a native filter widget or a custom header.

### FR-005: verify the existing rewind control

- Start with the existing ExoPlayer seek-back increment, `COMMAND_SEEK_BACK`,
  and media-button preference. Retain the shared `SelectionPlayer` progress
  recording path.
- Test a seekable narration while playing and paused, close to the beginning,
  after reconnect, and with the phone activity closed. Expected target is the
  greater of zero and current position minus 15 seconds, allowing normal player
  seek tolerance.
- If the command works but is not visible, inspect available commands and the
  Media3-to-legacy controller presentation before changing playback logic.
  Confirm any compatibility remedy works with the pinned Media3 1.8.0 version.
- Keep standard seek semantics and existing controller access checks. Do not
  duplicate standard seek with a custom session action or turn previous-track
  navigation into rewind as a shortcut.
- Record host-specific placement limits. Do not call this request verified
  solely because the source contains a command button.

### Delivery order and completion gates

Ticket 01 is skipped at the user's request. Step 2 is implemented in ticket 02;
later steps remain planned.

| Step | Scope | Completion gate |
| --- | --- | --- |
| 1 | Latest-build phone, DHU, and car verification | Skipped as a standalone ticket at the user's request. |
| 2 | Known-duration propagation and car labels, FR-001 | Implemented; 169 JVM tests pass, Android test sources compile, debug APK builds, and lint passes. Host acceptance remains in step 7. |
| 3 | Shared issue summaries, persistence, and updates, FR-002/FR-003 | Fractions match across views and survive refresh, completion, restart, and metadata eviction. |
| 4 | Car browse folders, FR-004, plus article-list summary placement | All three filters work with paging, live completion updates, empty states, and stable playback. |
| 5 | Rewind compatibility fix if host testing shows it is needed, FR-005 | Latest app has a usable 15-second rewind on the tested target host. This step can move earlier if rewind is broken. |
| 6 | Backend summary duration contract and data availability, remaining FR-001 | Known durations are available before first playback where the backend has narration metadata; absent fields remain compatible. |
| 7 | Integration and host acceptance | Run relevant automated checks and the phone/DHU/car matrix; record any remaining host limitations. |

## Testing Decisions

Use the existing boundaries, as confirmed by the user. The main acceptance
boundary is the media service as observed by a `MediaBrowser`/controller: returned
items, metadata, notifications, available commands, and playback behavior. Reuse
existing repository/catalog tests for deterministic metadata and persistence
cases, and existing Compose tests for the phone. No new production test facade
or parallel fake playback architecture is proposed.

Good tests assert externally observable results, not private helpers or the
number of calls between internal components. Network-call assertions are useful
where they prove a behavioral constraint, such as browsing never resolving audio.

| Area | Required cases | Existing coverage to extend |
| --- | --- | --- |
| Metadata contract | Optional duration present/absent/null; nonpositive values treated as unknown; older payload compatibility; newer known duration survives refresh; confirmed unnarrated clears it | `NetworkingTest`, `ArticleMetadataCacheTest` |
| Phone duration | Known duration is visible; unknown and unavailable narration keep their existing messages; refreshed rows receive cached duration | `ScreenStatesTest`, `NetworkScreensTest` |
| Car browse metadata | Duration and Listened prefixes precede long descriptions; title/author and completion extras remain correct; no stream URL is exposed | `MediaItemMapperTest`, `MediaLibrarySmokeTest` |
| Issue totals | Empty, unknown, partly played, completed, duplicate, shared-across-issues, and known-unnarrated articles; provisional eligibility changes; whole totals independent of page/filter | `CatalogLibraryTest`, `ListeningHistoryCacheTest` |
| Catalog persistence | Restart, old/corrupt snapshot, full-list eviction, membership retention, stale refresh, and totals from responses larger than the display cap | `CatalogLibraryTest` |
| Filter tree | Folder flags and lookup; invalid IDs; All/Unlistened/Listened ordering; filtering before pagination; empty success versus failure | `CatalogLibraryTest`, `MediaLibrarySmokeTest` |
| Live changes | Phone/car completion refreshes issue list and all affected parents with correct counts; multiple changed IDs are handled; active playback continues when a row disappears | `MediaLibrarySmokeTest`, `ListeningHistoryCacheTest` |
| Rewind | Command availability; 15-second movement within tolerance; clamp at zero; paused state and speed preserved; progress restored after reconnection | `SelectionPlayerTest`, `PlayerControlsTest`, `PlaybackResumptionTest` |

During implementation, run the relevant JVM tests first, then the project's
debug compile, instrumentation-source compile, APK build, and lint tasks. Run
connected instrumentation tests on a device/emulator with controlled metadata
and a local playable fixture for seek checks. A compiled instrumentation suite
does not establish that those tests passed on a device.

DHU and car acceptance must check cover-grid fraction visibility, article-list
heading visibility, long descriptions, all three filter paths, empty filters,
live completion, offline cached counts, and rewind in playing/paused states.
Check reconnect and phone-background behavior too. Host UI acceptance is
separate from automated service-contract acceptance.

## Out of Scope

- Changing the existing completion threshold or retroactively reconstructing
  listening history from before completion tracking existed.
- Cross-device/account synchronization, backend storage of personal listening
  history, manual mark-listened actions, and downloads.
- Total issue running time, remaining listening time, reading-time estimates,
  playback-speed-adjusted duration, and configurable rewind intervals.
- A custom Android Auto screen, migration to a different car app framework, or
  a general Media3/library upgrade without evidence that it is needed.
- Reimplementing phone listened filters or rewind when the latest build already
  satisfies those parts of the request.

## Further Notes

- Use “issue” for a dated magazine edition and “listened” for the existing local
  completion state.
- The counting population, folder navigation, 15-second interval, and text
  placement are proposed defaults. Revisit them if latest-build testing exposes
  a better supported presentation or the user changes the desired behavior.
- Android Auto owns browse presentation. The folder design above is an
  implementation proposal using the existing service architecture, supported by
  the [Android for Cars media-service guidance](https://developer.android.com/media/implement/surfaces/cars).
- Controllers decide which controls to show; button preferences express the
  desired presentation. The [Media3 playback-control guide](https://developer.android.com/media/media3/session/control-playback#manage-media-button-preferences)
  also directs apps to retain standard player commands for standard behavior.
- The [car playback-control guide](https://developer.android.com/training/cars/media/enable-playback)
  documents legacy-host behavior. Use it to assess compatibility, while keeping
  the existing Media3 service rather than adding a second session architecture.
- Extend the existing [Android Auto test guide](../../ANDROID_AUTO_TESTING.md) with
  actual host results during integration. Ticket 02 passed automated
  checks but did not install an APK or verify a phone/DHU/car host.
- This is the canonical spec in the local Markdown tracker. Follow the
  [tracker conventions](../../docs/agents/issue-tracker.md) when creating future
  implementation tickets. The `ready-for-agent` status records spec readiness;
  it does not claim that device verification or implementation has occurred.
