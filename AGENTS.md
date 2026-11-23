# Repository Workflow Rules

## Git & Branching
- Keep this repository on a single `main` branch (local and `origin`). For interactive user sessions, do not create feature or fix branches unless explicitly requested (even when a Review Council handoff brief asks for a `fix/...` branch).
- In interactive user sessions when explicitly authorized to publish Review Council fixes, commit directly on `main` and push to `origin/main` after the required tests pass. Automated Council audit, proof, and auto-patch workers must strictly honor their caller's branch, commit, and push restrictions and must never commit or push on their own.
- Always authenticate Git operations in this repository as GitHub user `skalamera` (configured via repo-local `.git/config`), never `stephen-hadrius`.
