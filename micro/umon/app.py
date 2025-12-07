#!/usr/bin/env python3
from time import monotonic
from http.server import BaseHTTPRequestHandler, HTTPServer
from os import environ
from logging import basicConfig, exception, DEBUG
from threading import Thread
from playwright.sync_api import sync_playwright

def fallback(fb, f, *args):
    try: return f(*args)
    except Exception as e:
        exception(e)
        return fb

def start_http_server(port, handle):
    def respond(exch, status, headers, content):
        exch.send_response(status)
        for k, v in headers: exch.send_header(k, v)
        exch.end_headers()
        exch.wfile.write(content)
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self): respond(self, *fallback((500, (), b''), handle, self))
    Thread(target=lambda: HTTPServer(("0.0.0.0", port), Handler).serve_forever(), daemon=True).start()

def main():
    port = int(environ["C4METRICS_PORT"])
    app = environ["C4MON_APP"]
    site = environ["C4MON_SITE"]
    selector = environ["C4MON_SELECTOR"]
    basicConfig(level=DEBUG)
    mut_content = {}
    start_http_server(port, lambda exch: mut_content.get(exch.path) or (404, (), b''))
    page = sync_playwright().start().chromium.launch(headless=True).new_context().new_page()
    page.goto(site)
    while True:
        started = monotonic()
        ok = 1 if fallback(None, lambda: page.wait_for_selector(selector, timeout=4000)) else 0
        lat = monotonic() - started
        content = f'c4synthetic_ok{{app="{app}"}} {ok}\nc4synthetic_latency_seconds{{app="{app}"}} {lat}\n'
        mut_content["/metrics"] = (200, [("Content-Type", "text/plain")], content.encode())
        page.wait_for_timeout(30000)
        page.reload()
