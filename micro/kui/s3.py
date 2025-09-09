
from json import loads
from pathlib import Path
from os import environ
import re

import boto3

def get_bucket_stats(s3, bucket):
    resp = s3.list_objects_v2(Bucket=bucket)
    objects = resp.get('Contents', [])
    is_truncated = resp["IsTruncated"]
    last_obj = objects[-1] if objects and not is_truncated else None
    return {
        "bucket_name": bucket,
        "objects_count": len(objects),
        "objects_size": sum(o['Size'] for o in objects),
        "is_truncated": is_truncated,
        **({
           "last_obj_key": last_obj['Key'], "last_obj_size": last_obj['Size'],
           "last_obj_mod_time": str(last_obj['LastModified'])
        } if last_obj else {})
    }

def do_search(s3context, executor, cond):
    conf, = [c for c in get_contexts() if c["name"] == s3context]
    secret = loads(Path(environ["C4KUI_S3_SECRETS"]).read_bytes())[s3context]
    s3r = boto3.resource(
        's3', aws_access_key_id=conf["access_key_id"], aws_secret_access_key=secret, endpoint_url=conf["endpoint"],
    )
    snapshot_buckets = [b.name for b in s3r.buckets.all() if cond(b.name)]
    s3c = s3r.meta.client # it seems safe for basic later ops
    return [*executor.map(lambda b: get_bucket_stats(s3c, b), snapshot_buckets)]

def get_contexts(): return loads(environ["C4KUI_S3_CONTEXTS"])

def init_s3(executor):
    mut_found_buckets_by_user = {}
    s3contexts = [c["name"] for c in get_contexts()]
    def load(mail, **_):
        return { "s3contexts": s3contexts, "items": mut_found_buckets_by_user.get(mail, []) }
    def search(mail, s3context, bucket_name_like='', **_):
        def run_search():
            mut_found_buckets_by_user[mail] = do_search(
                s3context, executor, 
                lambda nm: nm.endswith(".snapshots") and re.compile(bucket_name_like or ".").search(nm)
            )
        return run_search
    actions = { "s3.load": load, "s3.search": search }
    return actions
