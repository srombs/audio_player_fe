# Feature requests

Detailed follow-up: [implementation plan](.scratch/article-browsing-android-auto/spec.md),
including current source coverage, delivery order, and testing decisions.

Initial backlog. All items below are requested features; implementation and Android
Auto host support still need to be checked.

## FR-001 — Show article duration in article lists

- **Progress:** Known-duration propagation and car labels are implemented in
  [ticket 02](.scratch/article-browsing-android-auto/issues/02-show-known-article-durations.md).
  Backend metadata before first playback and host acceptance remain future work.
- **Platforms:** Phone app and Android Auto.
- **Request:** Show each article's audio duration in its list entry so listeners
  can see its length before selecting it.
- **Presentation:** Include the duration in the title or body/subtitle, depending
  on available space and Android Auto support (for example, `24 min`).
- **To decide:** Placement and what to show when the duration is unknown.

## FR-002 — Show magazine listening progress in the Android Auto article list

- **Platform:** Android Auto, article list within a magazine issue.
- **Request:** Show a fraction of listened articles out of the issue's total
  articles so listeners can see how much of the magazine they have finished.
- **Example:** `3/12 articles listened` in the issue heading or another supported
  location above the articles.

## FR-003 — Show listening progress for each magazine in Android Auto

- **Platform:** Android Auto, magazine list.
- **Request:** Add a listened/total article fraction to each magazine entry so
  listeners can check progress before opening an issue.
- **Example:** `3/12 articles listened` alongside the magazine title or subtitle.
- **Shared decision for FR-002 and FR-003:** Define whether the total includes all
  articles or only articles with narration. Keep the counts consistent between
  both views and use the app's existing listened/completion status.

## FR-004 — Filter Android Auto articles by listened status

- **Platform:** Android Auto, article list within a magazine issue.
- **Request:** Add a way to filter articles by whether they have been listened to.
- **Proposed options:** All, Unlistened, and Listened, matching the phone app's
  existing filters.
- **To check:** How Android Auto can expose these choices in its browse interface.

## FR-005 — Add a short rewind button to the Android Auto player

- **Platform:** Android Auto, player screen.
- **Request:** Provide a button that jumps backward within the current article.
- **To decide:** Rewind by 10 or 15 seconds per press.
- **To check:** How to expose this action through Android Auto's supported player
  controls.
