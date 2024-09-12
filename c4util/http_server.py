
from http.server import HTTPServer, BaseHTTPRequestHandler
from queue import Queue
from typing import NamedTuple

class PostResp(NamedTuple):
    status: int
    content_type: str
    text: str

class PostReq(NamedTuple):
    resp_q: Queue[PostResp]
    path: str
    text: str
    def respond_text(self, status, text):
        self.resp_q.put(PostResp(status,"text/plain",text))

def http_serve(req_q: Queue, addr):
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            req_text = self.rfile.read(int(self.headers['Content-Length'])).decode("utf-8")
            resp = http_q_exchange(req_q, self.path, req_text)
            self.send_response(resp.status)
            self.send_header("Content-Type", resp.content_type)
            self.end_headers()
            self.wfile.write(resp.text.encode("utf-8"))
    #noinspection PyTypeChecker
    HTTPServer(addr, CallHandler).serve_forever()

def http_q_exchange(req_q: Queue[PostReq], path: str, text: str) -> PostResp:
    resp_q: Queue[PostResp] = Queue()
    req_q.put(PostReq(resp_q, path, text))
    return resp_q.get()