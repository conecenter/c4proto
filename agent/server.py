
from os import environ
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from json import loads
import subprocess
from urllib.request import urlopen
from pathlib import Path
from sys import argv

def never(msg): raise Exception(msg)

def handle_get(kui_location, state_path, h: BaseHTTPRequestHandler):
    #print(threading.get_native_id())
    h.path.startswith("/agent-auth?") or never(f"bad path: {h.path}")
    with urlopen(f'{kui_location}{h.path}') as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(f.read().decode())
    for arg in msg["config_commands"]: subprocess.run(("kubectl","config",*arg), check=True)
    for arg in msg["pod_selectors"]: state_path.write_bytes(arg.encode())
    h.send_response(302)
    h.send_header('Location', msg["redirect"])
    h.end_headers()

def main(kui_location):
    state_path = Path(environ["KUBECONFIG"]).parent.parent / "c4pod"
    with Path("/tmp/c4pod") as p:
        p.unlink(missing_ok=True)
        p.symlink_to(state_path)
    class CallHandler(BaseHTTPRequestHandler):
        def do_GET(self): handle_get(kui_location, state_path, self)
    ThreadingHTTPServer((environ["C4AGENT_IP"],1979), CallHandler).serve_forever() # no pool; makes thread every request(or connection?)

main(*argv[1:])
