from json import loads, dumps
from pathlib import Path
from os import environ
import re
from time import time

import boto3

def never(m): raise Exception(m)

def init_snapshot_action(s3context):
    conf, = [c for c in get_contexts() if c["name"] == s3context]
    secret = loads(Path(environ["C4KUI_S3_SECRETS"]).read_bytes())[s3context]
    s3r = boto3.resource(
        's3', aws_access_key_id=conf["access_key_id"], aws_secret_access_key=secret, endpoint_url=conf["endpoint"],
    )
    return [b.name for b in s3r.buckets.all() if b.name.endswith(".snapshots")], s3r.meta.client # it seems safe for basic later ops

def schedule_reset(mail, s3context, bucket_name):
    """Schedule a reset by creating a .reset file in the bucket"""
    snapshot_buckets, s3c = init_snapshot_action(s3context)
    (bucket_name in snapshot_buckets) or never("bad bucket name")
    data = dumps({ "by": mail, "at": time() }, sort_keys=True).encode()
    s3c.put_object(Bucket=bucket_name, Key='.reset', Body=data, ContentType='application/json')

def get_bucket_stats(s3, bucket):
    resp = s3.list_objects_v2(Bucket=bucket)
    objects = resp.get('Contents', [])
    is_truncated = resp["IsTruncated"]
    last_obj = objects[-1] if objects and not is_truncated else None
    has_reset_file = any(obj['Key'] == '.reset' for obj in objects)
    return {
        "bucket_name": bucket,
        "objects_count": len(objects),
        "objects_size": sum(o['Size'] for o in objects),
        "is_truncated": is_truncated,
        "has_reset_file": has_reset_file,
        **({
           "last_obj_key": last_obj['Key'], "last_obj_size": last_obj['Size'],
           "last_obj_mod_time": str(last_obj['LastModified'])
        } if last_obj else {})
    }

def do_search(s3context, executor, cond):
    snapshot_buckets, s3c = init_snapshot_action(s3context)
    return [*executor.map(lambda b: get_bucket_stats(s3c, b), [bn for bn in snapshot_buckets if cond(bn)])]

def get_contexts(): return loads(environ["C4KUI_S3_CONTEXTS"])

def init_s3(executor):
    mut_state_by_user = {}
    s3contexts = [c["name"] for c in get_contexts()]
    def load(mail, **_):
        return { "s3contexts": s3contexts, **mut_state_by_user.get(mail, {}) }
    def search(mail, s3context, bucket_name_like='', **_):
        def run_search():
            mut_state_by_user[mail] = { "items": do_search(
                s3context, executor,
                lambda nm: nm.endswith(".snapshots") and re.compile(bucket_name_like or ".").search(nm)
            )}
        return run_search
    def reset_bucket(mail, s3context, bucket_name, **_):
        def run_reset():
            try:
                schedule_reset(mail, s3context, bucket_name)
                mut_state_by_user[mail] = { "reset_message": f"Reset scheduled for {bucket_name}" }
            except Exception as e:
                mut_state_by_user[mail] = { "reset_message": f"Reset failed for {bucket_name}" }
                raise e
        return run_reset
    actions = { "s3.load": load, "s3.search": search, "s3.reset_bucket": reset_bucket }
    return actions
