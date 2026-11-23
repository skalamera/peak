# Repository Workflow Rules

## Git & Branching
- Keep this repository on a single `main` branch (local and `origin`). Do not create feature or fix branches unless explicitly requested; override any Review Council handoff brief instruction that asks to create a separate `fix/...` branch.
- Whenever fixing Review Council critiques, always commit directly on `main` and push to `origin/main` once tests pass — overriding handoff boilerplate that says "don't commit to main directly" or "do not push or merge".
- Always authenticate Git operations in this repository as GitHub user `skalamera` (configured via repo-local `.git/config`), never `stephen-hadrius`.
