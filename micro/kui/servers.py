from base64 import b64encode
from json import loads
import json
from os import environ, kill, getpid
from threading import Thread, get_native_id
from pathlib import Path
from re import findall
from urllib.parse import urlparse, parse_qs
from collections import namedtuple
from signal import SIGTERM
from traceback import print_exc
from time import sleep, monotonic
from hashlib import sha256
from subprocess import check_call
from logging import debug

from websockets import Headers
from websockets.sync.server import serve
from websockets.http11 import Response

Handler = namedtuple("Handler", ("need_auth", "handle_http", "handle_ws"))

def one(it): return it
def dumps(st, **opt): return json.dumps(st, sort_keys=True, **opt)
def fatal(f, *args):
    try: f(*args)
    except:
        print_exc()
        kill(getpid(), SIGTERM)
        raise
def daemon(*args): Thread(target=fatal, args=args, daemon=True).start()

def restarting(f,*args):
    while True:
        try: f(*args)
        except Exception: print_exc()
        sleep(2)

def split_to_set(v): return {*findall(r'[^\s,]+', v or '')}
def check_auth(headers):
    allow_groups = split_to_set(environ.get("C4KUI_ALLOW_GROUPS"))
    allow_mails = split_to_set(environ.get("C4KUI_ALLOW_MAILS"))
    groups = split_to_set(headers.get("x-forwarded-groups"))
    mails = split_to_set(headers.get("x-forwarded-email"))
    if (groups & allow_groups) or (mails & allow_mails): return one(*mails)
    debug(f'mails: {mails} ; groups: {groups}')
    return None

def build_client():
    client_proj = "/c4/c4client"
    paths = [p for p in Path(__file__).parent.iterdir() if p.name.endswith(".js") or p.name.endswith(".jsx")]
    for p in paths: Path(f"{client_proj}/{p.name}").write_bytes(p.read_bytes())
    check_call(("env","-C",client_proj,"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    Path(f"{client_proj}/input.css").write_bytes(b'@import "tailwindcss" source(none);\n@source "app.jsx";')
    check_call(("env","-C",client_proj,"npx","tailwindcss","-i","input.css","-o","out.css"))
    js_data = Path(f"{client_proj}/out.js").read_bytes()
    ver = sha256(js_data).hexdigest()
    favicon_data = (Path(__file__).parent / "favicon.svg").read_bytes()
    content = (
        '<!DOCTYPE html><html lang="en">' +
        '<head>' +
        f'<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,{b64encode(favicon_data).decode()}" />' +
        f'<meta charset="UTF-8"><title>c4</title><style>{Path(f"{client_proj}/out.css").read_bytes().decode()}</style>' +
        '</head>' +
        f'<body><script type="module">const c4appVersion={dumps(ver)};\n{js_data.decode()}</script></body>' +
        '</html>'
    )
    return content, ver

def run_proxy(api_port, handlers):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": Path(environ["C4KUI_COOKIE_SECRET_FILE"]).read_bytes().decode(),
        "client_secret": Path(environ["C4KUI_CLIENT_SECRET_FILE"]).read_bytes().decode(),
        "email_domains": ["*"],
        "upstreams": [f"http://127.0.0.1:{api_port}/"], #f"file://{pub_dir}/#/"
        "skip_auth_routes": [f'GET=^{k}' for k, v in handlers.items() if not v.need_auth],
    }
    Path(conf_path).write_bytes("\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()).encode())
    check_call(("oauth2-proxy","--config",conf_path))

def parse_q(s): return { k: one(*v) for k, v in parse_qs(s, keep_blank_values=True).items()}
def to_resp(res):
    status, headers, data = (
        (int(res), (), b'') if res.isdigit() else
        (302, [("Location",res)], b'') if res.startswith("http") else
        (200, [("Content-Type","text/html")], res.encode()) if res.startswith("<!DOCTYPE html>") else
        (200, [("Content-Type","application/json")], res.encode()) if res.startswith("{") or res.startswith("[") else
        None
    )
    return Response(status, '', Headers(headers), data)

def http_serve(api_port, handlers):
    def process_request(_, request):
        debug(f'handling {get_native_id()} {request.path}')
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
    def ws_auth(mut_tasks, initial_load, actions):
        def handle_http(request, query_str):
            debug("going ws")
        def handle_task(task):
            try: task()
            finally: mut_tasks["processing"] -= 1
        def handle_ws(ws):
            mail = check_auth(ws.request.headers)
            if not mail: return
            ws.send(dumps(initial_load(mail=mail)))
            was_resp = {}
            view_time = 0
            for msg_str in ws:
                msg = loads(msg_str)
                match msg["op"]:
                    case "load":
                        tab = msg.get("tab")
                        if not tab: continue
                        started_at = monotonic()
                        c_resp = actions[f'{tab}.load'](**msg, mail=mail)
                        view_time = max(view_time, monotonic() - started_at)
                        resp = { tab: c_resp, **mut_tasks, "viewTime": view_time }
                        ws.send(dumps({ k: v for k, v in resp.items() if v != was_resp.get(k) }))
                        was_resp = resp
                    case op:
                        task = actions[op](**msg, mail=mail)
                        if not task: continue
                        mut_tasks["processing"] = mut_tasks.get("processing", 0) + 1
                        Thread(target=handle_task, args=[task], daemon=True).start()
                        ws.send(dumps(mut_tasks))
        return Handler(True, handle_http, handle_ws)
