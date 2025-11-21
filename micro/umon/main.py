#!/usr/bin/env python3
from time import monotonic
from os import environ
from prometheus_client import Gauge, start_http_server
from playwright.sync_api import sync_playwright

def tries(f):
    try: return f()
    except: return None

def main():
    SITE = environ["C4MON_SITE"]
    ok = Gauge("c4synthetic_ok", "Synthetic OK", ["site"])
    lat = Gauge("c4synthetic_latency_seconds", "Latency", ["site"])
    start_http_server(environ["C4METRICS_PORT"])
    page = sync_playwright().start().chromium.launch(headless=True).new_context().new_page()
    page.goto(f"https://{SITE}")
    while True:
        started = monotonic()
        ok.labels(SITE).set(1 if tries(lambda: page.wait_for_selector(environ["C4MON_SELECTOR"], timeout=4000)) else 0)
        lat.labels(SITE).set(monotonic() - started)
        page.wait_for_timeout(30000)
        page.reload()

if __name__ == "__main__": main()