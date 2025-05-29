
def http_serve(addr, routes: dict):
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            len_str = self.headers.get('Content-Length')
            routes[self.path](b'' if len_str is None else self.rfile.read(int(len_str)))
            self.send_response(200)
            self.end_headers()
    #noinspection PyTypeChecker
    ThreadingHTTPServer(addr, CallHandler).serve_forever()

# noinspection PyTypeChecker
def tcp_serve(addr, on_line, on_fin):
    from socketserver import ThreadingTCPServer, StreamRequestHandler
    class LogHandler(StreamRequestHandler):
        def handle(self):
            for line in self.rfile: on_line(line.rstrip(b'\n'))
            on_fin()
    class LogServer(ThreadingTCPServer):
        allow_reuse_address = True
    LogServer(addr, LogHandler).serve_forever()
