
from os import environ
from http.server import HTTPServer, BaseHTTPRequestHandler
from json import loads
import subprocess
from urllib.request import urlopen
from pathlib import Path
from hashlib import md5
from sys import argv

def never(msg): raise Exception(msg)

def handle_get(kui_location, h: BaseHTTPRequestHandler):
    h.path.startswith("/agent-auth?") or never(f"bad path: {h.path}")
    with urlopen(f'{kui_location}{h.path}') as f:
        f.status == 200 or never(f"bad status: {f.status}")
        msg = loads(f.read().decode())
    for arg in msg["certs"]:
        data = arg.encode()
        (Path(environ["KUBECONFIG"]).parent / f'hc-{md5(data).hexdigest()}.crt').write_bytes(data)
    for arg in msg["config_commands"]: subprocess.run(("kubectl","config",*arg), check=True)
    for arg in msg["pod_selectors"]: Path("/tmp/c4pod").write_bytes(arg.encode())
    h.send_response(303)
    h.send_header('Location', kui_location)
    h.end_headers()

def main(kui_location):
    class CallHandler(BaseHTTPRequestHandler):
        def do_GET(self): handle_get(kui_location, self)
    HTTPServer((environ["C4AGENT_IP"],1979), CallHandler).serve_forever()

main(*argv[1:])
