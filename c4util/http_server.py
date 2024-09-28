
from http.server import HTTPServer, BaseHTTPRequestHandler
from queue import Queue
from typing import NamedTuple

class PostReq(NamedTuple):
    path: str
    data: bytes

def http_serve(req_q: Queue, addr):
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            len_str = self.headers.get('Content-Length')
            req_q.put(PostReq(self.path, b'' if len_str is None else self.rfile.read(int(len_str))))
            self.send_response(200)
            self.end_headers()
    #noinspection PyTypeChecker
    HTTPServer(addr, CallHandler).serve_forever()
