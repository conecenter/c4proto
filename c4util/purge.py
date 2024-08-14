import json
import pathlib

from . import run_text_out, never_if, run
from .cluster import s3path, s3init, s3list, get_kubectl, get_active_prefixes


def filter_parts(check_prefix, postfix_set, values):
    return [
        value for value in values
        for parts in [value.split(".")]
        if len(parts) >= 2 and parts[-1] in postfix_set and check_prefix(".".join(parts[:-1]))
    ]


def s3purge(kc, need_rm):
    mc = s3init(kc)
    buckets = [it['key'] for it in s3list(mc, s3path(""))]
    buckets_to_rm = filter_parts(need_rm, {"snapshots/", "txr/"}, buckets)
    if buckets_to_rm:
        run((*mc, "rb", "--force", *(s3path(b) for b in buckets_to_rm)))


def secret_part_as_file(secret, file_name, to_dir):
    to_path = f"{to_dir}/{file_name}"
    pathlib.Path(to_path).write_bytes(secret(file_name))
    return to_path


def kafka_purge(kc, need_rm):
    topic_items = json.loads(run_text_out((*kc, "get", "kafkatopics", "-o", "json")))["items"]
    topics_to_rm = filter_parts(need_rm, {"inbox", }, [it["metadata"]["name"] for it in topic_items])
    if topics_to_rm:
        run((*kc, "delete", "kafkatopics", *topics_to_rm))


def purge_inner(kc, need_rm):
    s3purge(kc, need_rm)
    kafka_purge(kc, need_rm)


def purge_mode_list(deploy_context, mode_list):
    modes = {*mode_list}
    kc = get_kubectl(deploy_context)
    active_prefixes = get_active_prefixes(kc)
    purge_inner(kc, lambda prefix: prefix.split("-")[0] in modes and prefix not in active_prefixes)


def purge_prefix_list(deploy_context, prefix_list):
    prefixes = {*prefix_list}
    kc = get_kubectl(deploy_context)
    active_prefixes = get_active_prefixes(kc)
    never_if([f"{conflicting} is in use" for conflicting in sorted(prefixes & active_prefixes)])
    purge_inner(kc, lambda prefix: prefix in prefixes)
