import http.client
import json
import pathlib
import os


def main():
    vault_prefix = "/c4/vault/"
    env_vals = {v for k, v in os.environ.items() if k.startswith("C4") and v.startswith(vault_prefix)}
    if not env_vals: return
    kube_token = pathlib.Path("/var/run/secrets/kubernetes.io/serviceaccount/token").read_text()
    conn = http.client.HTTPConnection(os.environ["C4VAULT_HOST_PORT"])
    conn.request("POST", "/v1/auth/kubernetes/login", json.dumps({"jwt": kube_token, "role": "app"}))
    vault_token = json.loads(conn.getresponse().read())["auth"]["client_token"]
    for dir in sorted({"/".join(v.split("/")[:-1]) for v in env_vals}):
        secret_path = dir[len(vault_prefix):]
        pathlib.Path(dir).mkdir(parents=True)
        conn.request("GET", f"/v1/kv/data/{secret_path}", headers={"X-Vault-Token": vault_token})
        for key, content in json.loads(conn.getresponse().read())["data"]["data"].items():
            pathlib.Path(f"{dir}/{key}").write_text(content, encoding='utf-8', errors='strict')


main()
