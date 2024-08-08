import json
import pathlib
import time

from . import run_text_out, never_if, run, run_no_die
from .cluster import get_env_values_from_pods, s3path, s3init, s3list, get_kubectl, get_pods_json


def filter_parts(check_prefix, postfix_set, values):
    return [
        value for value in values
        for parts in [value.split(".")]
        if len(parts) >= 2 and parts[-1] in postfix_set and check_prefix(".".join(parts[:-1]))
    ]


def get_active_prefixes(kc): return get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", get_pods_json(kc, ()))


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


def purge_one_wait(kube_context, prefix):
    kc = get_kubectl(kube_context)
    while prefix in get_active_prefixes(kc):
        time.sleep(2)
    purge_inner(kc, lambda pr: pr == prefix)
    # s3 fix, when no bucket in list, but bucket has content
    mc = s3init(kc)
    for pf in ["snapshots","txr"]:
        bucket = s3path(f"{prefix}.{pf}")
        while run_no_die((*mc, "ls", "--json", bucket)):
            run((*mc, "rb", "--force", bucket))
            time.sleep(2)
    # wait no topic
    cmd = ("kafkacat", "-L", "-J", "-F", os.environ["C4KCAT_CONFIG"])
    topic = f"{prefix}.inbox"
    while any(t["topic"] == topic for t in json.loads(run_text_out(cmd))["topics"]):
        time.sleep(2)
