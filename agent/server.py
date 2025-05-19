
from os import environ
from http.server import HTTPServer, BaseHTTPRequestHandler
from json import loads
import subprocess
from urllib.request import urlopen
from pathlib import Path

def never(msg): raise Exception(msg)

def http_serve(addr, handle):
    class CallHandler(BaseHTTPRequestHandler):
        def do_GET(self): handle(self)
    HTTPServer(addr, CallHandler).serve_forever()

def handle_get(h):
    host = environ["C4KUI_HOST"]
    h.path.startswith("/agent-auth?") or never(f"bad path: {h.path}")
    with urlopen(f"https://{host}{h.path}") as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(f.read().decode('utf-8'))
    for cmd in msg["conf_commands"]: subprocess.run(("kubectl","config",*cmd), check=True)
    Path("/tmp/c4pod").write_text(msg["pod_selector"], encoding="utf-8", errors="strict")
    h.send_response(303)
    h.send_header('Location', f'https://{host}')
    h.end_headers()

def main():
    http_serve((environ["C4AGENT_IP"],1979), lambda h: handle_get(h))
