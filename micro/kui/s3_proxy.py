from json import loads
from re import fullmatch
from os import environ
from pathlib import Path

import boto3

def init_s3_proxy(rt):
    """
    Private S3 bucket -> authenticated KUI route.
    Env: C4KUI_S3_PROXY_CONF_DIR, C4KUI_S3_PROXY_PREFIX_TO_BUCKET (URL names do not expose bucket names)
    """
    prefix2bucket = loads(environ["C4KUI_S3_PROXY_PREFIX_TO_BUCKET"])
    def ld(k): return (Path(environ["C4KUI_S3_PROXY_CONF_DIR"])/k).read_bytes().decode().strip()
    client = boto3.client(
        "s3", endpoint_url=ld("address"), aws_access_key_id=ld("key"), aws_secret_access_key=ld("secret"),
    )
    def handle(request, parsed):
        def bad_path(): raise Exception(f"bad path: {parsed.path}")
        prefix, key = (fullmatch(r'/([0-9a-zA-Z-._]+)/([0-9a-zA-Z-._/]+)', parsed.path) or bad_path()).group(1,2)
        if ".." in key: bad_path()
        obj = client.get_object(Bucket=prefix2bucket[prefix], Key=key)
        data = obj["Body"].read()
        ctype = obj.get("ContentType")
        headers = [
            ("Content-Type", ctype),
        ]
        return rt.response(200, headers, data)
    return { f'/{b}/*': rt.http_auth_raw(handle) for b in prefix2bucket }
