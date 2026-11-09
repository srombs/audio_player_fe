# Domain docs

## Layout and reading rules

This is a single-context repository.

- Read root `GLOSSARY.md` before exploring domain concepts.
- Read relevant decisions under root `docs/adr/` before proposing changes in that area.
- If either is absent, proceed silently. Create domain documents lazily when the domain-modeling workflow resolves terminology or a decision.
- If the repository later introduces `GLOSSARY-MAP.md`, follow its links to the contexts relevant to the task.

## Vocabulary and decisions

Use the glossary's terms in specs, issues, implementation proposals, and tests.
When a needed concept is missing, check existing project language before proposing a glossary addition.

When a proposal conflicts with an ADR, name the ADR and explain the conflict explicitly rather than silently overriding it.
