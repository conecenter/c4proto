
def http_serve(addr, routes: dict):
    from http.server import HTTPServer, BaseHTTPRequestHandler
    class CallHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            len_str = self.headers.get('Content-Length')
            routes[self.path](b'' if len_str is None else self.rfile.read(int(len_str)))
            self.send_response(200)
            self.end_headers()
    #noinspection PyTypeChecker
    HTTPServer(addr, CallHandler).serve_forever()

def tcp_serve(addr, on_line, on_fin):
    from socketserver import ThreadingTCPServer, StreamRequestHandler
    class LogHandler(StreamRequestHandler):
        def handle(self):
            for line in self.rfile: on_line(line)
            on_fin()
    # noinspection PyTypeChecker
    ThreadingTCPServer(addr, LogHandler).serve_forever()
