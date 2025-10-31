# Repository Guidelines

## Project Structure & Module Organization
- `kui/` contains the control-plane app: Python drivers (`app.py`, `servers.py`, `agent_auth.py`) and React UI (`app.jsx`); keep infra utilities beside their consumers.
- `metrics4kafka/`, `s3client/`, `ws4cam/`, `sleep/`, and `memresize/` are worker containers with a `Dockerfile` plus `main.{py,java}`; create new services as sibling directories.
- Place assets (e.g., `kui/favicon.svg`) with the owning service and copy shared constants instead of importing across directories.

## Build, Test, and Development Commands
- `python -m venv .venv && . .venv/bin/activate` sets up a virtualenv for Python services.
- `pip install -r kui/requirements.txt` installs backend deps for KUI.
- `python kui/app.py` runs KUI; export `C4KUBECONFIG` to the kube contexts you observe.
- `docker build -t micro/<service>-dev <service>` builds dev images (e.g., `docker build -t micro/kui-dev kui`); mount your tree into `/app` because Dockerfiles stop after dependency layers.

## Coding Style & Naming Conventions
- Python code adheres to 4-space indentation and PEP 8 basics; keep helpers tight and side-effect light.
- React/JSX uses functional components and hooks; PascalCase components, camelCase utilities, minimal component state.
- Name scripts with kebab-case (`kube_top.py`, `s3_worker.py`) and keep support files inside the owning service directory.

## Commit & Pull Request Guidelines
- Follow the short, hyphenated subjects seen in history (`kui-thread-dump`, `micro-sleep`) and lead with the service name.
- Squash or rebase before opening a PR; describe behaviour changes, env vars, and operational impact, adding screenshots or logs when UX shifts.
- Link issue IDs and list required post-deploy steps in the PR body.

## Security & Configuration Tips
- Keep kubeconfigs, AWS credentials, and client secrets out of the repo; rely on runtime env vars such as `C4KUBECONFIG`.
- Dockerfiles are dev-only; deployment tooling adds `COPY <service>/ /app`. Pin dependencies in `requirements.txt` or language-specific manifests.

## Engineering Habits
- Intentional mutability: prefer comprehensions and single-assignment; name shared containers with `mut_` and replace entries wholesale.
- Concise/KISS: favor small, expression-heavy helpers and inline lambdas; keep React state lean.
- Minimal branching: guard upfront, return early, and keep happy paths linear.
- Fail fast: raise on broken invariants, then surface clear status at the boundary.
- Diff discipline: deliver small increments; stage large refactors and flag optional follow-ups.
- Default style deltas: Python allows tight expressions and manual wrapping; JavaScript mirrors our semicolon-free hooks style.
- Direct construction: build fresh dict/list snapshots (e.g., `replace_state` patterns) instead of piecemeal mutation.
