
import json
import base64
import os

from . import run_text_out, decode


def get_secret_data(kc, secret_name):
    secret = json.loads(run_text_out((*kc, "get", "secret", secret_name, "-o", "json")))
    return lambda secret_fn: base64.b64decode(secret["data"][secret_fn])


def secret_part_to_text(kc, k8s_path):
    secret_name, secret_fn = k8s_path.split("/")
    return decode(get_secret_data(kc, secret_name)(secret_fn))


def get_kubectl(kube_context): return "kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "--context", kube_context


def s3path(path): return f"def/{path}"


def get_any_pod(kc, label): return max(run_text_out((*kc, "get", "pods", "-o", "name", "-l", label)).splitlines())


def s3init(kc): return *kc, "exec", "-i", get_any_pod(kc, "c4s3client"), "--", "/tools/mc"


def s3list(mc, bucket): return [json.loads(line) for line in run_text_out((*mc, "ls", "--json", bucket)).splitlines()]


def get_env_values_from_pods(env_key, pods):
    return {e["value"] for p in pods for c in p["spec"]["containers"] for e in c.get("env", []) if e["name"] == env_key}


def get_all_contexts():
    cmd = ("kubectl", "--kubeconfig", os.environ["C4KUBECONFIG"], "config", "get-contexts", "-o", "name")
    return run_text_out(cmd).splitlines()


def get_pods_json(kc, add):
    return json.loads(run_text_out((*kc, "get", "pods", "-o", "json", *add), timeout=8))["items"]


def get_active_prefixes(kc): return get_env_values_from_pods("C4INBOX_TOPIC_PREFIX", get_pods_json(kc, ()))
