
import av
import faulthandler
from websockets.sync.server import serve
from websockets.exceptions import ConnectionClosedOK
from time import monotonic
from os import environ
from pathlib import Path
from itertools import count
from sys import stderr
from urllib.request import urlopen
from re import fullmatch

def log(text): print(text, file=stderr)

def handler(ws):
    req_id = next(counter)
    url = f'http://{environ["C4CAM_RESOLVER"]}/addr4key/{fullmatch(r"/mjpeg/([\w\-]+)", ws.request.path).group(1)}'
    log(f"{req_id} starting {url}")
    resp = urlopen(url)
    if resp.status != 200: raise Exception("bad response")
    cam_addr = resp.read().decode("utf-8")
    log(f"{req_id} resolved {cam_addr}")
    connected_at, last_received_at = (monotonic(), monotonic())
    try:
        with av.open(f"rtsp://{cred}@{cam_addr}",options={"rtsp_transport": "tcp"}) as container:
            log(f"{req_id} ready")
            for pkg in container.demux():
                data = bytes(pkg)
                if data[:2] == b'\xff\xd8': ws.send(data)
                else: log(f"{req_id} non-jpeg")
                now = monotonic()
                try:
                    ws.recv(timeout=0)
                    last_received_at = now
                except TimeoutError: pass
                if now - last_received_at > 5 or now - connected_at > 600: break
            log(f"{req_id} leaving")
    except ConnectionClosedOK:
        log(f"{req_id} ConnectionClosedOK")
    except av.error.ConnectionResetError:
        log(f"{req_id} ConnectionReset")

faulthandler.enable()
#faulthandler.dump_traceback_later(30, repeat=True)
counter = count()
cred = Path(environ["C4CAM_AUTH"]).read_text(encoding="utf-8", errors="strict")
serve(handler, "0.0.0.0", int(environ["C4CAM_SERVER_PORT"])).serve_forever()
