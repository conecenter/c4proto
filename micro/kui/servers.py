
from json import loads
from os import environ, kill, getpid
from threading import Thread, get_native_id
from pathlib import Path
from re import findall
from urllib.parse import urlparse, parse_qs
from collections import namedtuple
from signal import SIGTERM
from traceback import print_exc
from types import FunctionType

from websockets import Headers
from websockets.sync.server import serve
from websockets.http11 import Response

from util import one, log, read_text, write_text, run, never, dumps

Handler = namedtuple("Handler", ("need_auth", "handle_http", "handle_ws"))

def fatal(f, *args):
    try: f(*args)
    except:
        print_exc()
        kill(getpid(), SIGTERM)
        raise
def daemon(*args): Thread(target=fatal, args=args, daemon=True).start()

def split_to_set(v): return {*findall(r'\S+', v or '')}
def check_auth(headers):
    allow_groups = split_to_set(environ.get("C4KUI_ALLOW_GROUPS"))
    allow_mails = split_to_set(environ.get("C4KUI_ALLOW_MAILS"))
    groups = split_to_set(headers.get("x-forwarded-groups"))
    mails = split_to_set(headers.get("x-forwarded-email"))
    if (groups & allow_groups) or (mails & allow_mails): return one(*mails)
    log(f'mails: {mails} ; groups: {groups}')
    return None

def build_client():
    client_proj = "/c4/c4client"
    paths = [p for p in Path(__file__).parent.iterdir() if p.name.endswith(".js") or p.name.endswith(".jsx")]
    for p in paths: write_text(f"{client_proj}/{p.name}", read_text(p))
    run(("env","-C",client_proj,"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    write_text(f"{client_proj}/input.css", '@import "tailwindcss" source(none);\n@source "app.jsx";')
    run(("env","-C",client_proj,"npx","tailwindcss","-i","input.css","-o","out.css"))
    return lambda: (
            '<!DOCTYPE html><html lang="en">' +
            f'<head><meta charset="UTF-8"><title>c4</title><style>{read_text(f"{client_proj}/out.css")}</style></head>' +
            f'<body><script type="module">{read_text(f"{client_proj}/out.js")}</script></body>' +
            '</html>'
    )

def run_proxy(api_port, handlers):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": read_text(environ["C4KUI_COOKIE_SECRET_FILE"]),
        "client_secret": read_text(environ["C4KUI_CLIENT_SECRET_FILE"]),
        "email_domains": ["*"],
        "upstreams": [f"http://127.0.0.1:{api_port}/"], #f"file://{pub_dir}/#/"
        "skip_auth_routes": [f'GET=^/{k}' for k, v in handlers.items() if not v.need_auth],
    }
    write_text(conf_path, "\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()))
    run(("oauth2-proxy","--config",conf_path))

def parse_q(s): return { k: one(*v) for k, v in parse_qs(s, keep_blank_values=True)}
def to_resp(res):
    status, headers, data = (
        (int(res), (), b'') if res.isdigit() else
        (302, [("Location",res)], b'') if res.startswith("http") else
        (200, [("Content-Type","text/html")], res.encode()) if res.startswith("<!DOCTYPE html>") else
        (200, [("Content-Type","application/json")], res.encode()) if res.startswith("{") else
        never("bad result")
    )
    return Response(status, '', Headers(headers), data)

def http_serve(api_port, handlers):
    def process_request(_, request):
        log(f'handling {get_native_id()} {request.path}')
        parsed = urlparse(request.path)
        return handlers.get(parsed.path, handlers["_"]).handle_http(request, parsed.query)
    def handler(ws): return handlers[urlparse(ws.request.path).path].handle_ws(ws)
    serve(handler, "127.0.0.1", api_port, process_request=process_request).serve_forever()

class Route:
    @staticmethod
    def http_no_auth(handle):
        return Handler(False, lambda request, query_str: to_resp(handle(**parse_q(query_str))), None)
    @staticmethod
    def http_auth(handle):
        def handle_http(request, query_str):
            mail = check_auth(request.headers)
            return to_resp(handle(**parse_q(query_str), mail=mail) if mail else "403")
        return Handler(True, handle_http, None)
    @staticmethod
    def ws_auth(mut_tasks, actions):
        def handle_http(request, query_str):
            log("going ws")
        def handle_task(task):
            try:
                mut_tasks["processing"] = mut_tasks.get("processing", 0) + 1
                task()
            finally:
                mut_tasks["processing"] -= 1
        def handle_ws(ws):
            mail = check_auth(ws.request.headers)
            if not mail: return
            was_resp = {}
            for msg_str in ws:
                msg = loads(msg_str)
                match actions[msg["op"]](**mut_tasks, **msg, mail=mail):
                    case dict(resp):
                        ws.send(dumps({ k: v for k, v in resp.items() if v != was_resp.get(k) }))
                        was_resp = resp
                    case FunctionType() as task: Thread(target=handle_task, args=[task], daemon=True).start()

        return Handler(True, handle_http, handle_ws)
