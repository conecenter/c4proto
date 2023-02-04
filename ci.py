import json
import os
import pathlib
import tempfile

from c4util import parse_table
from c4util.build import kcd_args, run_text_out, never, run, get_env_values_from_deployments, setup_parser, \
    wait_processes, Popen, get_secret_data, decode


def filter_parts(check_prefix, postfix_set, values):
    return [
        value for value in values
        for parts in [value.split(".")] if len(parts) == 2 and parts[1] in postfix_set and check_prefix(parts[0])
    ]


def get_active_prefixes():
    deployments = json.loads(run_text_out(kcd_args("get", "deploy", "-o", "json")))["items"]
    return get_env_values_from_deployments("C4INBOX_TOPIC_PREFIX", deployments)


# def _run(args, **opt): print(args, opt)


def get_s3pod():
    s3args = kcd_args("get", "pods", "-o", "json", "-l", f"c4s3client")
    return json.loads(run_text_out(s3args))["items"][0]["metadata"]["name"]


def s3args(s3pod, *args): return kcd_args("exec", s3pod, "--", "/tools/mc", *args)


def s3path(path): return f"def/{path}"


def s3ls(s3pod, path):
    return [cells[-1] for cells in parse_table(run_text_out(s3args(s3pod, "ls", path)))]


def s3purge(need_rm):
    s3pod = get_s3pod()
    buckets = s3ls(s3pod, s3path(""))
    buckets_to_rm = filter_parts(need_rm, {"snapshots/", "txr/"}, buckets)
    if buckets_to_rm: run(s3args(s3pod, "rb", "--force", *(s3path(b) for b in buckets_to_rm)))


def secret_part_as_file(secret, file_name, to_dir):
    to_path = f"{to_dir}/{file_name}"
    pathlib.Path(to_path).write_bytes(secret(file_name))
    return to_path


def kafka_purge(need_rm):
    with tempfile.TemporaryDirectory() as temp_dir:
        coursier_res = run_text_out(["coursier", "fetch", "--classpath", "org.apache.kafka:kafka-clients:2.8.0"])
        classpath = coursier_res.split()[0]
        kafka_auth = get_secret_data("kafka-auth")
        kafka_certs = get_secret_data("kafka-certs")
        kafka_env = {
            **os.environ,
            "CLASSPATH": classpath,
            "C4STORE_PASS_PATH": secret_part_as_file(kafka_auth, "kafka.store.auth", temp_dir),
            "C4KEYSTORE_PATH": secret_part_as_file(kafka_certs, "kafka.keystore.jks", temp_dir),
            "C4TRUSTSTORE_PATH": secret_part_as_file(kafka_certs, "kafka.truststore.jks", temp_dir),
            "C4BOOTSTRAP_SERVERS": decode(kafka_auth("bootstrap_servers")),
        }
        proto_dir = os.environ["C4CI_PROTO_DIR"]
        kafka_cmd = ["java", "--source", "15", "-cp", classpath, f"{proto_dir}/kafka_info.java"]
        topics_res = run_text_out((*kafka_cmd, "topics"), env=kafka_env)
        topics = [cells[1] for cells in parse_table(topics_res) if cells[0] == "topic"]
        topics_to_rm = filter_parts(need_rm, {"inbox", }, topics)
        print(f"{len(topics)} topics found, {len(topics_to_rm)} to remove")
        topics_to_rm_str = "".join(f"{topic}\n" for topic in topics_to_rm)
        if topics_to_rm_str: run((*kafka_cmd, "topics_rm"), env=kafka_env, text=True, input=topics_to_rm_str)


def purge_inner(need_rm):
    s3purge(need_rm)
    kafka_purge(need_rm)


def handle_purge_mode_list(opt):
    modes = {*opt.list.split(",")}
    active_prefixes = get_active_prefixes()
    purge_inner(lambda prefix: prefix.split("-")[0] in modes and prefix not in active_prefixes)


def purge_prefix_list(prefixes):
    active_prefixes = get_active_prefixes()
    conflicting = prefixes & active_prefixes
    if conflicting: never(f"{conflicting} are in use")
    purge_inner(lambda prefix: prefix in prefixes)


def handle_purge_prefix_list(opt):
    purge_prefix_list({*opt.list.split(",")})


def with_zero_offset(fn):
    offset_len = 16
    offset, minus, postfix = fn.partition("-")
    return f"{'0' * offset_len}{minus}{postfix}" if minus == "-" and len(offset) == offset_len else None


def handle_clone_last_to_prefix_list(opt):
    to_prefix_list = opt.to.split(",")
    purge_prefix_list({*to_prefix_list})
    s3pod = get_s3pod()
    from_bucket = f"{opt.from_prefix}.snapshots"
    files = reversed(sorted(s3ls(s3pod, s3path(from_bucket))))
    from_fn, to_fn = next((fn, zfn) for fn in files for zfn in [with_zero_offset(fn)] if zfn)
    to_buckets = [f"{to_prefix}.snapshots" for to_prefix in to_prefix_list]
    for to_bucket in to_buckets: run(s3args(s3pod, "mb", s3path(to_bucket)))
    from_path = f"{from_bucket}/{from_fn}"
    to_paths = [f"{to_bucket}/{to_fn}" for to_bucket in to_buckets]
    wait_processes(Popen(s3args(s3pod, "cp", s3path(from_path), s3path(to))) for to in to_paths)


def main():
    opt = setup_parser((
        ("purge_mode_list", handle_purge_mode_list, ("--list",)),
        ("purge_prefix_list", handle_purge_prefix_list, ("--list",)),
        ("clone_last_to_prefix_list", handle_clone_last_to_prefix_list, ("--from-prefix", "--to")),
    )).parse_args()
    opt.op(opt)


main()
