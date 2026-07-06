# KUI dev notes / handoff

Scratch handoff for a fresh session. (AGENTS.md may be stale — it predates the HTTP rewrite below.)

## Architecture (current)

Plain **HTTP + Starlette + uvicorn** with **ETag caching**. There is **no WebSocket** anymore
(earlier WS diff-protocol / Cell / seq / kw_for discussion is obsolete — ignore it).

- **Requires free-threaded Python** (`servers.py`: `_is_gil_enabled() and die("gil_enabled")`). Won't start under a normal GIL build.
- Modules export `Op` (`rget`/`post`) and `Watch` (`watch`) values from `util.py`; `app.py` collects them with `flat_exports`.
- `servers.restarting(executor, [...watches, run_proxy, http_serve])` runs every `Watch.fn` in one shared
  `ThreadPoolExecutor(max_workers=256)` and **restarts** any that finish/raise (loop every 4s). `run_proxy` (oauth2-proxy)
  and `http_serve` (uvicorn) are themselves Watches.
- `http_serve.handle`: `msg = {**query_params, "mail": mail, "_no_auth": not mail, "path": url.path}`, then
  `handler(*(msg[k] for k in signature(fn).params))`. Response → ETag (blake2s); `if-none-match` match → 304.
- Client (`util.js`): `@tanstack/react-query`. `useRQuery` = GET polling; `useAppMutation` = POST + invalidate `['dynamic']`.
  `toPath({op,...args})` → `/op?sorted-query`. Browser handles `If-None-Match`/304.

## Conventions / gotchas

- **mail is server-injected** (query overridden in `handle`); the client never sends it, and can't spoof it.
- **All op-args travel in the query string; POST bodies are NOT read.** POST = `fetch(u,{method:"POST"})`, no body.
- **`handler(*(msg[k]...))` uses `msg[k]` (no `.get`)** → every handler param must be present in the request.
  The client sends every arg (`toPath` maps missing → `''`). Defaults on handler params are dead → don't add them.
  A param-name the client doesn't send → `KeyError` → 500 (silent drift risk).
- **Membership / anti-traversal = dict-lookup KeyError** (`kcs[ctx]`, `mut_log_paths[ctx]`).
- Handler exception → **500**. Client: query 500 → `*** Query Error ***` in the panel; mutation 500 → error banner
  (`opMessages[opOf(url)]`).

## Open issues (reviewed, deferred — see TODO in cio.py)

1. **Iteration races (rare, load-only).** `pods.load`, `cio_events.load`, `cio_tasks.load`, `cio_logs.load` iterate
   `.items()` on dicts mutated by other threads (watchers, or a concurrent `cio_logs.search`) with no lock.
   Free-threaded → `dictionary changed size during iteration` → intermittent 500 / `*** Query Error ***`.
   Fix: writer does whole-dict replace (reader grabs an immutable snapshot by ref) or a lock. (`list(d.items())` alone
   still races.) Dicts: `mut_resources` (kube_util writer), `mut_cio_statuses`, `mut_cio_proc_by_pid`, `mut_searches`.
2. `profiling` reset/download: `mut_pr.pop(mail)` / `mut_pr[mail]` / `mut_thread_dumps.pop(mail)` have no default →
   500 on empty/double-click; `profiling.thread_dump.html` download POPS (one-shot). Intentionally left — the error surfaces.
3. POST args in the URL → a big `logback_xml` (profiling.save_logback) can exceed URL length limits; fails as a clear
   fetch error (banner), not a silent truncation. Left as-is for now.
4. Shared pool: ~7×#contexts watchers + run_proxy + http_serve hold pool threads; requests use the same executor
   (uvicorn `limit_concurrency=32`). Fine now; watch at scale.
5. Per-poll blocking loads: `s3bucket.load` / `allure.load` run a kubectl-exec / boto3 call every ~15s per viewer
   (no watcher/cache; ETag only saves client bandwidth).

## Fixed in the review pass

- Removed the stale “all on the loop” comment; added the race TODO (item 1).
- Removed dead handler-param defaults (`s3.search`, `profiling.save`).
- `opMessages` revived: `onError` gets the toPath URL as the variable, so `opOf(url)=url.slice(1).split("?")[0]`
  recovers the op for the message.
- `toPath` sorts query keys → canonical URL (fixes `pendingMutations.includes(toPath(act))` busy match + stabler cache keys).

## Testing note

Can't run the app in this scratch env (needs free-threaded Python + websockets/starlette/uvicorn + a cluster).
Python files `py_compile` clean; JS is bundled by `build.py` on the target.
