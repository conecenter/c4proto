# KUI Agent Notes

## Overview
- `app.py` is the entrypoint used by `tini` in the Docker image; it wires every subsystem and finally serves HTTP/WebSocket traffic on `127.0.0.1:1180`.
- `servers.py` provides the lightweight server runtime (OAuth2 proxy bootstrap, HTTP routing, WebSocket loop) and builds the React frontend bundle on startup via `/c4/c4client`.
- The UI (`app.jsx`, `util.js`) is a single WebSocket client. It keeps URL hash parameters in sync with the requested tab/filters and sends `{op: ...}` messages. Each tab registers a `{tab}.load` action plus extra actions for user gestures.
- Stateful data is cached per requestor: each module maintains `mut_*` dictionaries that are mutated by background threads and read from WebSocket handlers.

## Runtime Flow
1. `app.main()` configures logging, parses JSON configuration from environment variables, and starts a `ThreadPoolExecutor` for long-running async tasks.
2. Agent auth, Kubernetes, CIO, profiling, and S3 modules register WebSocket actions and HTTP endpoints; watchers are started in daemon threads using `servers.daemon`.
3. `Route.ws_auth` performs auth against forwarded headers, pushes an initial snapshot (`load_shared` + tab data), and then multiplexes user operations. Functions handling non-blocking work return a callable; the runtime runs it in a separate thread and updates `processing` state.
4. `http_serve` (from `websockets.sync.server`) hosts both WebSockets and HTTP endpoints on the same port; `run_proxy` wraps everything with `oauth2-proxy`.

## Key Modules & Responsibilities
- `agent_auth.py`: Implements one-time-code flows for per-cluster OIDC tokens. Uses `C4KUI_CLUSTERS`, `C4KUI_CONTEXTS`, file-backed client secrets/certs, and emits shell-friendly kubeconfig commands plus pod selectors for port-forwarding.
- `kube_util.py`: Subscribes to Kubernetes watches for pods/services/ingresses in every context and keeps `mut_resources[(kind, kube_context)][name]` up to date.
- `kube_pods.py`: Supplies `pods.load` (filtered list enriched with metrics), `pods.select_pod`, `pods.recreate_pod`, and `pods.scale_down`. Uses `kubectl` via the global kubeconfig and watches maintained in `mut_resources`.
- `kube_top.py`: Periodically refreshes metrics API snapshots into `mut_metrics[(key, kube_context)]`. `pods.load` triggers refreshes by touching `mut_metrics[("expired", kube_context)]`.
- `cio.py`: Talks to the `c4cio` service in each active context. Three subsystems cover task queues, event feeds, and log streaming/search (with temporary files in a process-local directory).
- `s3.py`: S3 snapshot tooling. `init_s3` (bucket list) and `init_s3bucket` (object view) expose:
  - `s3.load`: returns cached state (`mut_state_by_user[mail]`) plus any status message.
  - `s3.search`: enqueues a parallel scan of buckets (names ending in `.snapshots`) using the shared executor.
  - `s3.reset_bucket`: writes a `.reset` object into the bucket to signal an external reset job.
  - `s3bucket.load`: enqueues a bucket refresh and serves cached objects keyed by `(context, bucket)`.
  - `s3bucket.refresh`: forces a bucket refresh; a background watcher drains the queue and updates the shared cache.
  Credentials and endpoints come from `C4KUI_S3_CONTEXTS` plus secrets JSON stored at `C4KUI_S3_SECRETS`.
- `profiling.py`: Launches async-profiler inside selected pods. Tracks profiling status/result per user and serves the generated flamegraph over `/profiling-flamegraph.html`.
- `servers.py`: OAuth2 proxy config, WebSocket/HTTP routing, response helpers, and the frontend build step (esbuild + Tailwind).
- `util.js`: WebSocket management, hash-param navigation, and small React hooks (`useSimpleInput`, `useTabs`).

## WebSocket Contract
- Each tab’s primary loader responds to `{ op: "{tab}.load", ...filters }`.
- Actions returning work (e.g., `pods.recreate_pod`, `cio_logs.search`, `s3.reset_bucket`) must return a callable; `Route.ws_auth` increments `processing`, runs the callable in a background thread, and pushes updated shared state when complete.
- Updates are diffed before sending (`was_resp`) to reduce chatter; only changed keys are transmitted.

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
| `C4KUI_S3_CONTEXTS` | JSON array of S3 contexts (name, endpoint, access key). |
| `C4KUI_S3_SECRETS` | JSON file containing per-context secret keys. |
| `C4KUI_DEBUG` | Any value enables DEBUG logging. |

## External Requirements
- Docker image installs: `oauth2-proxy`, `node@22`, `kubectl@1.33`, `async-profiler`, Python 3.12 + `websockets`.
- `/c4/c4client` must exist with dependencies; `build_client` syncs local JS/JSX files into that directory before bundling.
- S3 access uses `boto3.resource(..., endpoint_url=conf["endpoint"])`; contexts are expected to describe non-AWS endpoints.

## Development Tips
- Watch for shared mutable dictionaries (`mut_resources`, `mut_metrics`, etc.). They are updated from background threads without locking; consumers treat them as eventually consistent.
- Many functions assume the relevant dictionaries are populated; guard for missing keys when adding new features.
- Long-running shell commands (`kubectl`, `tar`, `grep`) are invoked synchronously; consider executor usage if you introduce extra latency.
- The frontend assumes every new tab follows the existing filter pattern: store filters in hash, call `willSend` to trigger actions, and expect the backend to echo new state on completion.
- Treat the `mut_*` containers as mutable maps with immutable payloads: replace values wholesale instead of mutating nested keys so diffing and concurrency stay predictable.
- Loader functions should stay non-blocking: queue background work and return a snapshot immediately; watchers are responsible for driving refreshes.
- Each module should return watcher callables from its `init_*` entry point; the caller (usually `app.py`) owns thread creation and restart handling.

## Style & Conventions
- **Single orchestrator**: `app.py` owns composition. Individual modules should stay focused on their own concerns and avoid importing one another directly (pass callbacks/data through `init_*` hooks instead).
- **Micro-framework core**: Treat `servers.py` + `util.js` as the home-grown HTTP/WebSocket framework. Prefer reusing their helpers (e.g., `Route.ws_auth`, `manageExchange`) instead of introducing new dispatch layers.
- **Intentional mutability**: Most data flows favor immutable expressions (list/dict comprehensions, single-assignment variables). Any shared mutable structure must be clearly named with a `mut_` prefix to surface side effects.
- **Concise/KISS**: Keep implementations short and direct—lean on expressions, inline `lambda`s, and comprehensions rather than auxiliary classes or elaborate abstractions. Mirror this in JSX: no semicolons, rely on hash-param helpers for navigation, and keep component state minimal.
- **Minimal branching**: Guard upfront, return early, and keep the “happy path” straight so helpers read top-to-bottom without nested conditionals.
- **Fail fast**: Validate required config/inputs immediately and raise when invariants break; translate those exceptions into user-facing errors only at the outermost boundary.
- **Surface failures**: Log errors and surface a clear status to the user, but avoid returning raw exception objects or tracebacks over WebSocket payloads.
- **Diff discipline**: Prefer small, incremental diffs. If broader refactors are unavoidable, stage them stepwise and call out optional pieces so reviews stay quick.
- **Default style deltas**: Python keeps close to PEP 8 but tolerates tightly-packed expressions; manual line wrapping and imports should remain tidy. JavaScript/JSX sticks with modern React, omits semicolons, and spells out every `useEffect` dependency; follow the existing patterns when extending the UI.
- **Direct construction**: Prefer comprehensions and whole-object replacements (`replace_state` style factories) over piecemeal mutation so cached snapshots stay easy to diff and reason about.
