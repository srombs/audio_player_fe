# Issue tracker: Local Markdown

Specs and tickets live in this repository under `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`.
- The canonical spec is `.scratch/<feature-slug>/spec.md`.
- Implementation tickets are separate files at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`.
- Record triage state with a `Status:` line near the top of each spec or ticket. Use the role strings in `triage-labels.md`.
- Append discussion under `## Comments`, preserving earlier entries.
- Record dependencies near the top as `Blocked by: NN, NN`, using ticket numbers within the same feature directory.
- Preserve existing specs and tickets when updating them.

## Skill operations

- **Publish to the issue tracker:** create or update the appropriate local spec or ticket.
- **Fetch a ticket:** read its referenced path, or locate its number within the feature directory.
- **Apply a label:** update its `Status:` line using the configured triage role.
- **Resolve a ticket:** record the outcome and set `Status: resolved`. Use `wontfix` for declined work.
- **Claim a ticket:** set `Status: claimed` before beginning implementation.

## Wayfinding

- The map is `.scratch/<feature-slug>/map.md`, containing Notes, Decisions-so-far, and Fog.
- Each child is a separate numbered ticket. Its `Type:` is `research`, `prototype`, `grilling`, or `task`.
- A ticket is unblocked when every listed blocker is resolved.
- Select the first open, unblocked, unclaimed ticket by number; claim it before working.
- On resolution, append the answer under `## Answer` and add a short finding and link to the map's Decisions-so-far.
