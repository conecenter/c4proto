from json import loads
from itertools import groupby
from os import environ
from pathlib import Path
from queue import SimpleQueue
from re import fullmatch
from time import monotonic, time

import boto3


def grouped(l): return [(k,[v for _,v in kvs]) for k,kvs in groupby(sorted(l, key=lambda kv: kv[0]), lambda kv: kv[0])]

def init_allure():
    prefix2bucket = loads(environ["C4KUI_S3_PROXY_PREFIX_TO_BUCKET"])
    bucket = prefix2bucket["allure"]
    conf_dir = Path(environ["C4KUI_S3_PROXY_CONF_DIR"])
    ld = lambda k: (conf_dir / k).read_bytes().decode().strip()
    client = boto3.client(
        "s3", endpoint_url=ld("address"), aws_access_key_id=ld("key"), aws_secret_access_key=ld("secret"),
    )
    cache = {}
    requests = SimpleQueue()

    # CIO owns producing immutable Allure artifacts; KUI owns listing/rendering them.
    # The WebSocket client sends tab loads repeatedly while visible, so loads only enqueue
    # refresh work and return cached state. expires_at is the soft-refresh dedupe gate,
    # matching the non-blocking S3 bucket view pattern.
    def load(mail, **_):
        requests.put(True)
        cached = cache.get("allure")
        return cached["state"] if cached else create_response(None, None, None)

    def refresh(mail, **_):
        requests.put(False)

    def create_response(items, error, loaded_at):
        return { "items": items, "error": error, "loaded_at": loaded_at }

    def replace_state(state, ttl):
        cache["allure"] = { "state": state, "expires_at": monotonic() + ttl }

    def list_root_names():
        paginator = client.get_paginator("list_objects_v2")
        pages = paginator.paginate(Bucket=bucket, Delimiter="/")
        return [
            name
            for page in pages
            for name in [
                *[p["Prefix"] for p in page.get("CommonPrefixes") or []],
                *[o["Key"] for o in page.get("Contents") or []],
            ]
        ]

    def parse_run(name):
        match = fullmatch(r'run\.([^.]+)\.([^.]+)\.(unp/|tgz)', name)
        return match and {
            "ts": match.group(1),
            "project": match.group(2),
            "kind": "html" if match.group(3) == "unp/" else "tgz",
            "run": f"run.{match.group(1)}.{match.group(2)}",
            "href": name,
        }

    def group_runs(names):
        parts = [r for r in (parse_run(name) for name in names) if r]
        return [
            {
                "run": run,
                "ts": group[0]["ts"],
                "project": group[0]["project"],
                "html": next((r["href"] for r in group if r["kind"] == "html"), None),
                "tgz": next((r["href"] for r in group if r["kind"] == "tgz"), None),
            }
            for run, group in reversed(grouped((r["run"], r) for r in parts))
        ]

    def watcher():
        while True:
            is_soft = requests.get()
            cached = cache.get("allure")
            if cached and is_soft and cached["expires_at"] > monotonic():
                continue
            try:
                replace_state(create_response(group_runs(list_root_names()), None, time()), 15)
            except Exception:
                replace_state(create_response(None, "Failed to refresh Allure reports. Try again later.", time()), 15)
                raise

    return (
        {
            "allure.load": load,
            "allure.refresh": refresh,
        },
        watcher,
    )
