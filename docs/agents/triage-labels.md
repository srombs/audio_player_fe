# Triage labels

Map canonical skill roles to these local `Status:` values:

| Skill role | Local status | Meaning |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | Maintainer needs to evaluate the request |
| `needs-info` | `needs-info` | Waiting for more information |
| `ready-for-agent` | `ready-for-agent` | Fully specified and ready for agent implementation |
| `ready-for-human` | `ready-for-human` | Requires human implementation |
| `wontfix` | `wontfix` | Declined work |

For example: `Status: ready-for-agent`.

Use this mapping when a skill asks to apply a triage label. Implementation lifecycle states such as `claimed` and `resolved` are defined in `issue-tracker.md`.
