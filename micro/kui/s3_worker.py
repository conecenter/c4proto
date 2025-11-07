from json import loads, dumps
from os import environ
from re import search
from time import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

import boto3

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

def handle_search(bucket_name_like):
    executor = ThreadPoolExecutor(max_workers=16)
    cond = lambda nm: search(bucket_name_like or ".", nm)
    snapshot_buckets, s3c = init_snapshot_action()
    print(dumps([*executor.map(lambda b: get_bucket_stats(s3c, b), [bn for bn in snapshot_buckets if cond(bn)])]))

def init_snapshot_action():
    load = lambda k: (Path(environ["C4S3_CONF_DIR"]) / k).read_bytes().decode().strip()
    s3r = boto3.resource(
        's3', aws_access_key_id=load("key"), aws_secret_access_key=load("secret"), endpoint_url=load("address"),
    )
    return [b.name for b in s3r.buckets.all() if b.name.endswith(".snapshots")], s3r.meta.client # it seems safe for basic later ops

def handle_schedule_reset(bucket_name):
    """Schedule a reset by creating a .reset file in the bucket"""
    snapshot_buckets, s3c = init_snapshot_action()
    if bucket_name not in snapshot_buckets: raise Exception("bad bucket name")
    s3c.put_object(Bucket=bucket_name, Key='.reset', Body=b'')

def handle_list_objects(bucket_name):
    snapshot_buckets, s3c = init_snapshot_action()
    if bucket_name not in snapshot_buckets: raise Exception("bad bucket name")
    resp = s3c.list_objects_v2(Bucket=bucket_name)
    objects = [
        {
            "key": obj["Key"],
            "size": obj["Size"],
            "last_modified": str(obj.get("LastModified")),
        }
        for obj in resp.get("Contents") or []
    ]
    print(dumps({
        "bucket_name": bucket_name,
        "objects": objects,
        "too_many": resp.get("IsTruncated", False),
    }))
