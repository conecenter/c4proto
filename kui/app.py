
import subprocess
from json import loads, dumps
from os import environ, kill, getpid
from threading import Thread
from signal import SIGINT
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler

def decode(bs): return bs.decode('utf-8')
def encode(v): return v.encode("utf-8")
def write_text(path, text): Path(path).write_text(text, encoding="utf-8", errors="strict")
def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')
def run(args, **opt): return subprocess.run(args, check=True, **opt)
def fatal(f, *args):
    res = []
    try: res.append(f(*args))
    finally: len(res) > 0 or kill(getpid(), SIGINT)
def daemon(*args): Thread(target=fatal, args=args, daemon=True).start()

###

def outer_handle_json_post(h, handlers):
    #todo check group
    len_str = h.headers.get('Content-Length')
    in_msg = loads(decode(h.rfile.read(int(len_str))))
    out_msg = handlers[in_msg["op"]](in_msg)
    if out_msg:
        out_data = encode(dumps(out_msg, sort_keys=True))
        h.send_response(200)
        h.send_header("Content-Type", "application/json")
        h.end_headers()
        h.wfile.write(out_data)
    else:
        h.send_response(200)
        h.end_headers()

def http_serve(addr, handlers):
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self): outer_handle_json_post(self, handlers)
    #noinspection PyTypeChecker
    HTTPServer(addr, CallHandler).serve_forever()

def build_client(pub_dir):
    client_proj = "/c4/c4client"
    write_text(f"{client_proj}/app.jsx", read_text(Path(__file__).parent/"app.jsx"))
    run(("env","-C",client_proj,"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    js_content = read_text(f"{client_proj}/out.js")
    html_content = (
        '<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>c4</title></head><body><script type="module">' +
        js_content + '</script></body></html>'
    )
    Path(pub_dir).mkdir(parents=True, exist_ok=True)
    write_text(f'{pub_dir}/index.html', html_content)

def run_proxy(pub_dir, api_port):
    conf_path = "/c4/oauth2-proxy.conf"
    proxy_conf = {
        "cookie_secret": read_text(environ["C4KUI_COOKIE_SECRET_FILE"]),
        "client_secret": read_text(environ["C4KUI_CLIENT_SECRET_FILE"]), "provider": "oidc",
        "email_domains": ["*"], "insecure_oidc_allow_unverified_email": True, "oidc_groups_claim": "cognito:groups",
        "upstreams": [f"file://{pub_dir}/#/", f"http://127.0.0.1:{api_port}/kop"],
    }
    write_text(conf_path, "\n".join(f'{k} = {dumps(v)}' for k, v in proxy_conf.items()))
    run(("oauth2-proxy","--config",conf_path))

###

def handle_get_state():
    return {"hi":"world"}

def get_handlers(): return {
    "get_state": lambda m: handle_get_state(),
}

def main():
    pub_dir = "/c4/c4pub"
    api_port = 1180
    build_client(pub_dir)
    daemon(run_proxy, pub_dir, api_port)
    http_serve(("127.0.0.1",api_port), get_handlers())
