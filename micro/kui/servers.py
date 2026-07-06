from collections.abc import Callable
from functools import partial
from json import dumps
from concurrent.futures import Future
from os import environ
from pathlib import Path
from re import findall
from subprocess import check_call
from sys import _is_gil_enabled
from threading import get_native_id
from typing import Sequence
from logging import debug, exception
from inspect import signature, Parameter
from time import sleep
from hashlib import blake2s
from asyncio import get_running_loop

import uvicorn
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.gzip import GZipMiddleware
from starlette.responses import Response
from starlette.routing import Route

from util import die, Export, grouped, Op, Watch, rget

def flat_exports(l): return [(e if isinstance(e, Export) else die(f"bad_export {e}")) for es in l for e in es]

def split_to_set(v): return {*findall(r'[^\s,]+', v or '')}
def check_auth(headers):
    allow_groups = split_to_set(environ.get("C4KUI_ALLOW_GROUPS"))
    allow_mails = split_to_set(environ.get("C4KUI_ALLOW_MAILS"))
    groups = split_to_set(headers.get("x-forwarded-groups"))
    mails = split_to_set(headers.get("x-forwarded-email"))
    if ((groups & allow_groups) or (mails & allow_mails)) and len(mails) == 1: return next(iter(mails))
    debug(f'mails: {mails} ; groups: {groups}')
    return None

def run_proxy(api_port, exports: Sequence[Export]):
    handlers = [e for e in exports if isinstance(e, Op)]
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": Path(environ["C4KUI_COOKIE_SECRET_FILE"]).read_bytes().decode(),
        "client_secret": Path(environ["C4KUI_CLIENT_SECRET_FILE"]).read_bytes().decode(),
        "email_domains": ["*"],
        "upstreams": [f"http://127.0.0.1:{api_port}/"], #f"file://{pub_dir}/#/"
        "skip_auth_routes": [f'GET=^{e.path}' for e in handlers if "_no_auth" in signature(e.fn).parameters],
    }
    Path(conf_path).write_bytes("\n".join(f'{k} = {dumps(v, sort_keys=True)}' for k, v in proxy_conf.items()).encode())
    check_call(("oauth2-proxy","--config",conf_path))

def restarting(executor, exports: Sequence[Export]):
    fns = [e.fn for e in exports if isinstance(e, Watch)]
    _is_gil_enabled() and die("gil_enabled")
    mut_tasks: dict[Callable[[], None],Future] = {}
    while True:
        for fn in fns:
            t = mut_tasks.get(fn)
            if t and t.done(): exception("restartable failed", exc_info=None if t.cancelled() else t.exception())
            if not t or t.done(): mut_tasks[fn] = executor.submit(fn)
        sleep(4)

def make_response(status, headers=(), data=b''): return Response(data, status_code=status, headers=dict(headers))
def make_html_response(data): return make_response(200, [("Content-Type","text/html")], data)
def http_serve(executor, api_port, exports: Sequence[Export]):
    def handle(handler, params, req):
        debug(f'handling {get_native_id()}')
        mail = check_auth(req.headers)
        if not mail and "_no_auth" not in params: return make_response(403)
        msg = { **dict(req.query_params), "mail": mail, "_no_auth": not mail, "path": req.url.path }
        res = handler(*(msg[k] for k in params))
        if res is None: return make_response(200)
        if isinstance(res, Response): return res
        res_b = dumps(res, sort_keys=True).encode()
        etag = blake2s(res_b, digest_size=8).hexdigest()
        with_cache_head = (("Cache-Control", "private, no-cache"), ("ETag", etag))
        return (
            make_response(304, with_cache_head) if etag == req.headers.get("if-none-match") else
            make_response(200, (*with_cache_head, ("Content-Type","application/json")), res_b)
        )
    async def endpoint(is_command, fns, req):
        return await get_running_loop().run_in_executor(executor, handle, is_command, fns, req)
    def run():
        handlers = [e for e in exports if isinstance(e, Op)]
        routes = [
            Route(path, partial(endpoint, fn, params), methods=["POST" if is_command else "GET"])
            for (path, is_command), fns in grouped(((h.path, h.is_command), h.fn) for h in handlers)
            for fn in fns if len(fns)==1 or die(f"conflicting_handlers {path}")
            for ps in [signature(fn).parameters]
            for params in [[(die(f"VAR {n}") if p.kind is Parameter.VAR_KEYWORD else n) for n, p in ps.items()]]
        ]
        middleware = [Middleware(GZipMiddleware, minimum_size=1000, compresslevel=9)]
        app = Starlette(routes=routes, middleware=middleware)
        uvicorn.run(app, host="127.0.0.1", port=api_port, limit_concurrency=32)
    run()

def init_system_routes():
    @rget("/shared")
    def meta(mail): return {"mail": mail, "app_version": app_ver.decode()}
    @rget("/")
    def load_index(): return make_html_response(index_content)
    index_content, app_ver = [(Path(__file__).parent/n).read_bytes() for n in ("app.html", "app.ver")]
    return meta, load_index
