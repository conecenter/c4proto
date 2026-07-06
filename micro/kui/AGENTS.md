# KUI Agent Notes

## Overview
- `build.py` builds the React frontend bundle (esbuild + Tailwind).
- `app.py` is the entrypoint used by `tini` in the Docker image; it wires every subsystem ("manual DI" for modules) and finally serves HTTP traffic on `127.0.0.1:1180`.
- `servers.py` provides the lightweight server runtime (OAuth2 proxy and starlette wrap).
- Multiple module `init_*`-s can return handlers: watchers (for daemon threads), HTTP endpoints.
- Stateful data is cached: modules maintains `mut_*` dictionaries that are mutated and read by watchers and handlers.
- `http_serve` hosts HTTP endpoints; `run_proxy` wraps everything with `oauth2-proxy`.
- The UI (`app.jsx`, `util.js`) is a single client, where `util.js` is more abstract (unaware of concrete modules). 
- Client keeps URL hash parameters in sync with the selected tab/filters.
- Client sends `{op: ..., args: ...}` messages; server performs auth against forwarded headers and multiplexes messages to op-handlers.
- op-handlers are for periodic tab content fetch and mutating operations.
- etag-s are implemented for load results to reduce chatter.
- Operations run in parallel in a thread pool. Client maintains processing state.

## Key Modules & Responsibilities
- `agent_auth.py`: Implements one-time-code flows for per-cluster OIDC tokens. Uses `C4KUI_CLUSTERS`, `C4KUI_CONTEXTS`, file-backed client secrets/certs, and emits shell-friendly kubeconfig commands plus pod selectors for port-forwarding.
- `kube_util.py`: Subscribes to Kubernetes watches for pods/services/ingresses in every context and keeps `mut_resources[(kind, kube_context)][name]` up to date.
- `kube_pods.py`: Supplies pods (filtered list enriched with metrics) and pod operations. Uses `kubectl` via the global kubeconfig and watches maintained in `mut_resources`.
- `kube_top.py`: Periodically refreshes metrics API snapshots into `mut_metrics[(key, kube_context)]`. Pod lister triggers refreshes by touching `mut_metrics[("expired", kube_context)]`.
- `cio.py`: Talks to the `c4cio` service in each active context. Three subsystems cover task queues, event feeds, and log streaming/search (with temporary files in a process-local directory).
- `s3.py`: S3 snapshot tooling. `init_s3` (bucket list) and `init_s3bucket` (object view) expose:
- `profiling.py`: Launches async-profiler inside selected pods. Tracks profiling status/result per user and serves the generated flamegraph over `/profiling.flamegraph.html`.
- `allure.py`: Lists Allure report artifacts from the S3 proxy `allure` bucket and serves cached rows for the Allure tab.
- `servers.py`: OAuth2 proxy config, routing, response helpers.
- `util.js`: exchange management, hash-param navigation, and small React hooks.

## Environment Variables
| Variable | Purpose |
| --- | --- |
| `C4KUI_CONTEXTS` | JSON array of Kube contexts (name, namespace, cluster, watch flag). |
| `C4KUI_CLUSTERS` | JSON array of cluster metadata (name, zone, optional issuer/grafana URL). |
| `C4KUBECONFIG` | Path to the kubeconfig used for all `kubectl` calls and watches. |
| `C4KUI_ALLOW_GROUPS`, `C4KUI_ALLOW_MAILS` | Comma/space separated auth allowlists read from proxy headers. |
| `C4KUI_COOKIE_SECRET_FILE`, `C4KUI_CLIENT_SECRET_FILE` | File paths consumed by OAuth2 proxy. |
| `C4KUI_CLIENT_SECRETS` | JSON mapping of cluster name → client secret (used for agent auth). |
| `C4KUI_CERTS` | Template path containing `{name}` placeholder pointing to cluster CA bundles. |
| `C4KUI_API_SERVER` | Template for API server URL (`{name}` substituted per cluster). |
| `C4KUI_HOST` | External hostname used in redirects. |
| `C4KUI_ISSUER` | Template for default OIDC issuer (`{zone}` substitution). |
| `C4KUI_LINKS`, `C4KUI_GRAFANA` | Custom links and Grafana URL template for the Links tab. |
| `C4KUI_S3_SECRETS` | JSON file containing per-context secret keys. |
| `C4KUI_DEBUG` | Any value enables DEBUG logging. |

## External Requirements & Development Tips
- S3 access expects non-AWS endpoints.
- Modules should return watcher callables from its `init_*` entry point; the caller (`app.py`) passes them to `restarting` for thread creation and restart handling.

## Style & Conventions
- **Single orchestrator**: `app.py` owns composition. Individual modules should stay focused on their own concerns and avoid importing one another directly (pass callbacks/data through `init_*` hooks instead).
- **Micro-framework core**: Treat `servers.py` + `util.js` as the home-grown HTTP framework. Prefer reusing their helpers instead of introducing new dispatch layers.
- **Intentional mutability**: Most data flows favor immutable expressions (list/dict comprehensions, single-assignment variables). Any shared mutable structure must be clearly named with a `mut_` prefix to surface side effects. Treat the `mut_*` containers as mutable maps with immutable payloads: replace values wholesale instead of mutating nested keys so diffing and concurrency stay predictable.
- **Concise/KISS**: Keep implementations short and direct—lean on expressions, inline `lambda`s, and comprehensions rather than auxiliary classes or elaborate abstractions. Mirror this in JSX: no semicolons, rely on hash-param helpers for navigation, and keep component state minimal.
- **Minimal branching**: Guard upfront, return early, and keep the “happy path” straight so helpers read top-to-bottom without nested conditionals.
- **Fail fast**: Validate required config/inputs immediately and raise when invariants break; translate those exceptions into user-facing errors only at the outermost boundary.
- **Surface failures**: Log errors and surface a clear status to the user, but avoid returning raw exception objects or tracebacks over response payloads.
- **Diff discipline**: Prefer small, incremental diffs. If broader refactors are unavoidable, stage them stepwise and call out optional pieces so reviews stay quick.
- **Default style deltas**: Python keeps close to PEP 8 but tolerates tightly-packed expressions; manual line wrapping and imports should remain tidy. JavaScript/JSX sticks with modern React, omits semicolons, and spells out every `useEffect` dependency; follow the existing patterns when extending the UI.
- **Guard via known state**: When commands depend on user-supplied selectors (contexts, pod names, PIDs, file paths), prefer checking membership in the authoritative cache/list or probing with a cheap command (`ls`, etc.) instead of layering `None`/empty checks. Reject anything that fails the lookup so handlers stay concise and resilient to fabricated inputs.
- **Direct construction**: Prefer comprehensions and whole-object replacements over piecemeal mutation so cached snapshots stay easy to diff and reason about.
- **Subprocess safety**: use async `check_output`, never a bare `run` whose `check=True` is easy to forget; read/write **bytes** with explicit `.decode()`/`.encode()` instead of `text=True` (locale- and version-independent; `.decode()` defaults to utf-8).
