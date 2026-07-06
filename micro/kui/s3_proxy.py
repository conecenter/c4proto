
from json import loads
from pathlib import Path
from re import fullmatch
from os import environ

from boto3 import client

from util import die, rget

def init_s3client():
    ld = lambda k: Path(f'{environ["C4KUI_S3_PROXY_CONF_DIR"]}/{k}').read_bytes().decode().strip()
    return client("s3", endpoint_url=ld("address"), aws_access_key_id=ld("key"), aws_secret_access_key=ld("secret"))

def init_s3_proxy(client, make_response):
    """
    Private S3 bucket -> authenticated KUI route.
    Env: C4KUI_S3_PROXY_CONF_DIR, C4KUI_S3_PROXY_PREFIX_TO_BUCKET (URL names do not expose bucket names)
    """
    prefix2bucket = loads(environ["C4KUI_S3_PROXY_PREFIX_TO_BUCKET"])
    def handle(path):
        def bad_path(): die(f"bad path: {path}")
        prefix, key = (fullmatch(r'/([0-9a-zA-Z-._]+)/([0-9a-zA-Z-._/]+)', path) or bad_path()).group(1,2)
        if ".." in key: bad_path()
        obj = client.get_object(Bucket=prefix2bucket[prefix], Key=key)
        data = obj["Body"].read()
        ctype = obj.get("ContentType")
        headers = [
            ("Content-Type", ctype),
        ]
        return make_response(200, headers, data)
    return [rget(f'/{b}/{{rest:path}}')(handle) for b in prefix2bucket]
