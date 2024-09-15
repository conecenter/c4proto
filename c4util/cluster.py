
import json
import base64
import os
from http.client import HTTPConnection
from time import sleep

from . import run_text_out, repeat, http_exchange


def get_secret_data(kc, secret_name):
    secret = json.loads(run_text_out((*kc, "get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])


def get_secret_part(kc, k8s_path):
    secret_name, secret_fn = k8s_path.split("/")
    return get_secret_data(kc, secret_name)(secret_fn)


def get_kubectl(kube_context): return "kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "--context", kube_context


def s3path(path): return f"def/{path}"


def get_any_pod(kc, label): return max(run_text_out((*kc, "get", "pods", "-o", "name", "-l", label)).splitlines())


def s3init(kc): return *kc, "exec", "-i", get_any_pod(kc, "c4s3client"), "--", "/tools/mc"


def s3list(mc, bucket): return [json.loads(line) for line in run_text_out((*mc, "ls", "--json", bucket)).splitlines()]


def get_prefixes_from_pods(pods): return get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", pods)


def get_env_values_from_pods(env_key, pods):
    return {e["value"] for p in pods for c in p["spec"]["containers"] for e in c.get("env", []) if e["name"] == env_key}


def get_all_contexts():
    cmd = ("kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "config", "get-contexts", "-o", "name")
    return run_text_out(cmd).splitlines()


def get_pods_json(kc, add):
    return json.loads(run_text_out((*kc, "get", "pods", "-o", "json", *add), timeout=8))["items"]


def wait_no_active_prefix(kc, prefix):
    repeat(lambda: sleep(2) if prefix in get_prefixes_from_pods(get_pods_json(kc, ())) else True, (True,))


def kafka_port(o): return 9000+o
def kafka_post(id, op, prefix):
    conn = HTTPConnection("127.0.0.1", kafka_port(id))
    return http_exchange(conn, "POST", f"/{op}/{prefix}.inbox")
