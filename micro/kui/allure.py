from json import loads
from os import environ
from re import fullmatch
from time import time

from util import grouped, rget

def s3list(client, bucket): return [*client.get_paginator("list_objects_v2").paginate(Bucket=bucket, Delimiter="/")]

def init_allure(client):
    prefix2bucket = loads(environ["C4KUI_S3_PROXY_PREFIX_TO_BUCKET"])
    bucket = prefix2bucket["allure"]
    # CIO owns producing immutable Allure artifacts; KUI owns listing/rendering them.
    # matching the non-blocking S3 bucket view pattern.
    @rget("/allure")
    def load(): return { "items": group_runs(list_root_names()), "loaded_at": time() }

    def list_root_names():
        return [
            name
            for page in s3list(client, bucket)
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

    return load,
