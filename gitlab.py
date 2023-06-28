#!/usr/bin/python3 -u

import base64
from re import search, sub, match
import sys
from os import environ as e
from json import dumps, dump, loads, load
import subprocess
from pathlib import Path
import http.client
from tempfile import TemporaryDirectory
from time import sleep, monotonic


def sorted_items(d): return sorted(d.items())


def never(a): raise Exception(a)


def debug(text):
    print(text, file=sys.stderr)


def run(args, **opt):
    debug("running: " + " ".join(args))
    return subprocess.run(args, check=True, **opt)


def connect():
    conn = http.client.HTTPSConnection(e["CI_SERVER_HOST"], int(e["CI_SERVER_PORT"]))
    project_url = "/api/v4/projects/" + e["CI_PROJECT_ID"]
    return conn, project_url


def exchange(conn_url, method, resource, data):
    conn, project_url = conn_url
    token_header = {"PRIVATE-TOKEN": e["C4CI_TOKEN"]}
    headers = {"Content-Type": "application/json", **token_header} if data else token_header
    conn.request(method, project_url + "/" + resource, dumps(data) if data else None, headers)
    resp = conn.getresponse()
    return resp.status, loads(resp.read())


def need_tag(conn_url, tag_name):
    status, res = exchange(conn_url, "POST", "repository/tags", {"tag_name": tag_name, "ref": e['CI_COMMIT_SHA']})
    debug((status, res))
    # if status == 201:
    #     debug(f"tag created: {tag_name}")
    #     return
    # # we get 400 already exists
    # status, res = exchange(conn_url, "GET", "repository/tags/"+tag_name, None)
    # if status == 200 and res["target"] == e['CI_COMMIT_SHA']:
    #     debug(f"tag exists: {tag_name}")
    #     return
    # never((status, res))
    # # we get 404 Not Found -- let pipeline fail later


def post_pipeline(conn_url, tag_name, variables):
    variables_list = [{"key": k, "value": v} for k, v in sorted_items(variables)]
    status, res = exchange(conn_url, "POST", "pipeline", {"ref": tag_name, "variables": variables_list})
    if status == 201:
        debug(f"pipeline created: {res['web_url']}")
        return res["id"]
    never((status, res))


def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')


def over_bytes(f, text): return f(text.encode('utf-8')).decode('utf-8')


def handle_generate():
    conf = load(sys.stdin)
    script_body = read_text(sys.argv[0]).replace("'"+"C4GITLAB_CONFIG_JSON"+"'", dumps(conf))
    out = {
        "stages": ["develop", "deploy", "start", "stop"],
        ".handler": {
            "before_script": [
                "mkdir -p $CI_PROJECT_DIR/c4gitlab",
                "export PATH=$PATH:$CI_PROJECT_DIR/c4gitlab",
                f"echo '{over_bytes(base64.b64encode, script_body)}' | base64 -d > $CI_PROJECT_DIR/c4gitlab/c4gitlab",
                "chmod +x $CI_PROJECT_DIR/c4gitlab/c4gitlab",
            ],
            "variables": {"GIT_DEPTH": 10}, "needs": [],
        },
        ".rule.build.common": {"rules": [{"if": f"$CI_PIPELINE_SOURCE == \"push\" && $CI_COMMIT_BRANCH"}]},
        ".rule.deploy.any": {"rules": [{"when": "manual", "if": f"$C4GITLAB_AGGR"}]},
        ".rule.deploy.prod":
            {"rules": [{"when": "manual", "if": f"$C4GITLAB_AGGR && $CI_COMMIT_TAG =~ /\\/release\\//"}]},
        ".rule.env.start": {"rules": [
            {"if": f"$C4GITLAB_ENV_NAME =~ /^cl-/", "when": "manual"},
            {"if": f"$C4GITLAB_ENV_NAME"}
        ]},
        ".rule.env.stop": {"rules": [{"when": "manual", "if": f"$C4GITLAB_ENV_NAME"}]},
        "init": {
            "extends": [".handler", ".rule.build.common"], "script": ["c4gitlab measure init"],
            "stage": "develop"
        },
        ".no_git": {"variables": {"GIT_STRATEGY": "none"}},
        **{
            caption_mask: {
                "extends": [".handler", ".rule.deploy.prod" if env_mask == "cl-prod" else ".rule.deploy.any"],
                "script": [f"c4gitlab measure deploy '{env_mask}'"],
                "stage": {"de": "develop", "sp": "develop", "cl": "deploy"}[env_mask.partition("-")[0]],
            } for op, env_mask, caption_mask in conf if op == "C4DEPLOY"
        },
        "start": {
            "extends": [".handler", ".rule.env.start", ".no_git"],
            "script": ["c4gitlab measure start"], "stage": "start",
            "environment": {
                "name": "$C4GITLAB_ENV_GROUP/$C4GITLAB_ENV_NAME", "action": "start",
                "on_stop": "stop", "url": "$C4GITLAB_ENV_URL"
            }
        },
        "stop": {
            "extends": [".handler", ".rule.env.stop", ".no_git"],
            "script": ["c4gitlab measure stop"], "stage": "stop",
            "environment": {
                "name": "$C4GITLAB_ENV_GROUP/$C4GITLAB_ENV_NAME", "action": "stop"
            }
        },
    }
    dump(out, sys.stdout, sort_keys=True, indent=4)


def handle_init():
    conf = 'C4GITLAB_CONFIG_JSON'  # this line will be preprocessed
    branch = e["CI_COMMIT_BRANCH"]
    tag_name_by_aggr = {
        aggr: "t4/"+branch+"."+e["CI_COMMIT_SHORT_SHA"]+"."+aggr
        for op, aggr, cond in conf if op == "C4AGGR_COND" and search(cond, branch)
    }
    subj = sub("[^a-z]", "", branch.rpartition("/")[-1].lower())
    conn_url = connect()
    for aggr, tag_name in sorted_items(tag_name_by_aggr):
        need_tag(conn_url, tag_name)
        post_pipeline(conn_url, tag_name, {"C4GITLAB_AGGR": aggr, "C4GITLAB_SUBJ": subj})


def handle_deploy(env_mask):
    env_state = (
        env_mask.replace("{C4USER}", sub("[^a-z]", "", e["GITLAB_USER_LOGIN"].lower()))
        .replace("{C4SUBJ}", e["C4GITLAB_SUBJ"]).replace("{C4AGGR}", e["C4GITLAB_AGGR"])
    )
    temp_root = TemporaryDirectory()
    out_path = f"{temp_root.name}/out.json"
    run(("c4ci_prep", "--context", e["CI_PROJECT_DIR"], "--env-state", env_state, "--info-out", out_path))
    start_info_raw = read_text(out_path)
    info = loads(start_info_raw)
    stop_info_raw = dumps({**info, "state": "c4-off", "manifests": []}, sort_keys=True)
    conn_url = connect()
    mans = info["manifests"]
    name, = {man["metadata"]["labels"]["c4env"] for man in mans}
    group, = {man["metadata"]["labels"]["c4env_group"] for man in mans}
    urls = [
        f"https://{h}"
        for man in mans if man["kind"] == "Ingress" for tls in man["spec"].get("tls", []) for h in tls["hosts"]
    ]
    variables = {
        "C4GITLAB_ENV_GROUP": group, "C4GITLAB_ENV_NAME": name, "C4GITLAB_ENV_URL": max([""]+urls),
        "C4GITLAB_ENV_INFO_START": over_bytes(base64.b64encode, start_info_raw),
        "C4GITLAB_ENV_INFO_STOP": over_bytes(base64.b64encode, stop_info_raw),
    }
    pipeline_id = post_pipeline(conn_url, e["CI_COMMIT_TAG"], variables)
    while True:
        status, jobs = exchange(conn_url, "GET", f"pipelines/{pipeline_id}/jobs", None)
        job = next((j for j in jobs if j["name"] == "start"), None)
        job_status = job["status"]
        debug((job_status, job["web_url"]))
        if job_status == "success":
            break
        if job_status == "failed" or job_status == "canceled":
            never(job_status)
        sleep(5)


def handle_measure(script, *args):
    started = monotonic()
    with subprocess.Popen((script, *args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
        for line in proc.stdout:
            print(f"{str(int(monotonic()-started)).zfill(5)} {line}", end="")
        proc.wait()
    sys.exit(proc.returncode)


def main(script, op, *args):
    {
        "generate": handle_generate,
        "measure": lambda *a: handle_measure(script, *a),
        "init": handle_init,
        "deploy": handle_deploy,
        "start": lambda: run(("c4ci_up",), text=True, input=over_bytes(base64.b64decode, e["C4GITLAB_ENV_INFO_START"])),
        "stop": lambda: run(("c4ci_up",), text=True, input=over_bytes(base64.b64decode, e["C4GITLAB_ENV_INFO_STOP"])),
    }[op](*args)


main(*sys.argv)
