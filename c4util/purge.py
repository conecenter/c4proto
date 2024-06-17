import json
import os
import pathlib
import tempfile

from . import parse_table, log, run_text_out, never_if, run, decode
from .cluster import get_env_values_from_pods, s3path, s3init, s3list, get_kubectl, get_secret_data, get_pods_json


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
    temp_dir_life = tempfile.TemporaryDirectory()
    temp_dir = temp_dir_life.name
    coursier_res = run_text_out(["coursier", "fetch", "--classpath", "org.apache.kafka:kafka-clients:2.8.0"])
    classpath = coursier_res.split()[0]
    kafka_auth = get_secret_data(kc, "kafka-auth")
    kafka_certs = get_secret_data(kc, "kafka-certs")
    kafka_env = {
        **os.environ,
        "JAVA_TOOL_OPTIONS": "",
        "CLASSPATH": classpath,
        "C4STORE_PASS_PATH": secret_part_as_file(kafka_auth, "kafka.store.auth", temp_dir),
        "C4KEYSTORE_PATH": secret_part_as_file(kafka_certs, "kafka.keystore.jks", temp_dir),
        "C4TRUSTSTORE_PATH": secret_part_as_file(kafka_certs, "kafka.truststore.jks", temp_dir),
        "C4BOOTSTRAP_SERVERS": decode(kafka_auth("bootstrap_servers")),
    }
    proto_dir = os.environ.get("C4CI_PROTO_DIR")
    kafka_cmd = ["java", "--source", "15", f"{proto_dir or ''}/kafka_info.java"]
    topics_res = run_text_out((*kafka_cmd, "topics"), env=kafka_env)
    topics = [cells[1] for cells in parse_table(topics_res) if cells[0] == "topic"]
    topics_to_rm = filter_parts(need_rm, {"inbox", }, topics)
    log(f"{len(topics)} topics found, {len(topics_to_rm)} to remove")
    topics_to_rm_str = "".join(f"{topic}\n" for topic in topics_to_rm)
    if topics_to_rm_str:
        run((*kafka_cmd, "topics_rm"), env=kafka_env, text=True, input=topics_to_rm_str)


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
