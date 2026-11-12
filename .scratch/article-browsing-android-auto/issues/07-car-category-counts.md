# Ticket 07 — Match Android Auto category counts to their labels

Status: resolved
Type: task
Blocked by: 04

Part of [the feature spec](../spec.md), FR-002 and FR-004. This supersedes
ticket 04's shared progress text in filter-folder titles.
Branch: `ticket-07-car-category-counts`, based on `main` (`22970f2`).

## Problem

The user's DHU screenshot shows All, Unlistened, and Listened each displaying
`0/16 listened` in both the title and subtitle. The category contents are
correct, but Unlistened's text describes completed articles instead of the
remaining articles, and each row repeats its count.

## Confirmed behavior

With 5 of 16 eligible articles listened:

| Title | Subtitle |
| --- | --- |
| All | 5/16 listened |
| Unlistened | 11/16 unlistened |
| Listened | 5/16 listened |

Titles contain only category names. Show each count once, in its subtitle.
All retains the listened count. Denominators use complete eligible issue
membership rather than the filtered or paginated article list.

## Acceptance criteria

- Return the three category names without counts in both title metadata fields.
- Show listened/total for All and Listened, and (total minus listened)/total
  with the word `unlistened` for Unlistened.
- Handle none, some, and all listened articles, including the reported 0/16 case.
  A confirmed empty issue uses 0/0; unavailable membership reports
  `Progress unavailable` without an invented fraction.
- Refresh subtitles on completion and narration eligibility changes. Preserve
  consistent metadata between child listings and item lookup, including offline
  restoration and complete membership beyond the article-detail cache limit.
- Preserve existing eligibility, completion, filtering, pagination, identity,
  magazine progress, and playback behavior.

## Answer

Implemented on 2026-11-12. `CatalogLibrary.filterNode` derives the category's
subtitle from the existing whole-issue summary and supplies a category-only
title. Magazine progress presentation and the Media3 mapper remain unchanged.
README and the canonical spec describe the confirmed presentation.

Regression coverage exercises the exact 16-article scenario at 0, 5, and 16
completed articles, plus empty issues, unknown membership, offline restoration,
eligibility, completion notifications across 200 retained issues, and totals
beyond the article-detail cache. Updated tests failed before the formatting fix.
All 190 JVM tests pass, and the real MediaBrowser smoke test passes on the
connected Pixel 10 Pro (Android 17). The connected test checks category title
and subtitle metadata, item lookup, completion refresh, and unchanged idle
playback state. It does not establish visual DHU/car acceptance or continuity
while audio is actively playing.

Validation command:

```sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.rombs.newyorkeraudio.playback.MediaLibrarySmokeTest#cachedFilterTreeAndHistoryChangesReachRealBrowserWithoutChangingPlaybackState'
```

Result: build successful; 190 unit tests and 1 connected test, with no failures
or errors. `./gradlew :app:lintDebug` and `git diff --check` also pass. Visual
acceptance of the updated category rows remains part of delivery step 7.

## Comments

### 2026-11-12 — User confirmation and delivery

The user confirmed both the category-specific fractions and category-only titles
with counts shown once in subtitles. After implementation and automated checks,
the user requested a local ticket, a new branch, and a pull request.
