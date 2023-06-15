#!/usr/bin/python3 -u

import base64
from re import search, sub, match
import sys
from os import environ as e
from json import dumps, dump, loads, load
import subprocess
from pathlib import Path
import http.client
import tempfile
from time import sleep, monotonic


def sorted_items(d): return sorted(d.items())


def never(a): raise Exception(a)


def debug(text):
    print(text, file=sys.stderr)


def run(args, **opt):
    debug("running: " + " ".join(args))
    return subprocess.run(args, check=True, **opt)


def connect():
    conn = http.client.HTTPSConnection(e["CI_SERVER_HOST"], e["CI_SERVER_PORT"])
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
    if status == 201:
        debug(f"tag created: {tag_name}")
        return
    status, res = exchange(conn_url, "GET", "repository/tags/"+tag_name, None)
    if status == 200 and res["target"] == e['CI_COMMIT_SHA']:
        debug(f"tag exists: {tag_name}")
        return
    never((status, res))


def post_pipeline(conn_url, tag_name, image, variables):
    variables_list = [{"key": k, "value": v} for k, v in sorted_items(variables)]
    variables_list_all = [{"key": "C4GITLAB_IMAGE", "value": image}, *variables_list]
    status, res = exchange(conn_url, "POST", "pipeline", {"ref": tag_name, "variables": variables_list_all})
    if status == 201:
        debug(f"pipeline created: {res['web_url']}")
        return res["id"]
    never((status, res))


def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')


def get_deploy_jobs(env_mask, key_mask):
    rule = ".rule.deploy.prod" if env_mask == "cl-prod" else ".rule.deploy.any"
    deploy_stages = {"de": "develop", "sp": "develop", "cl": "deploy"}
    confirm_key = key_mask.replace("$C4CONFIRM", "confirm")
    confirm_key_opt = [confirm_key] if confirm_key != key_mask else []
    return [
        *[(k, {
            "extends": [".common_job", rule], "script": ["c4gitlab confirm"], "stage": "confirm"
        }) for k in confirm_key_opt],
        (key_mask.replace("$C4CONFIRM", "deploy"), {
            "extends": [".common_job", rule], "script": [f"c4gitlab measure deploy '{env_mask}'"],
            "stage": deploy_stages[env_mask.partition("-")[0]], "needs": confirm_key_opt
        })
    ]


def handle_generate():
    conf = load(sys.stdin)
    script_body = read_text(sys.argv[0]).replace("'"+"C4GITLAB_CONFIG_JSON"+"'", dumps(conf))
    script_body_encoded = base64.b64encode(script_body.encode('utf-8')).decode('utf-8')
    stages = ["develop", "confirm", "deploy", "start", "stop"]
    env_gr_name = "$C4GITLAB_ENV_GROUP/$C4GITLAB_ENV_NAME"
    jobs = {
        ".handler": {"before_script": [
            "mkdir -p $CI_PROJECT_DIR/c4gitlab",
            "export PATH=$PATH:$CI_PROJECT_DIR/c4gitlab",
            f"echo '{script_body_encoded}' | base64 -d > $CI_PROJECT_DIR/c4gitlab/c4gitlab",
            "chmod +x $CI_PROJECT_DIR/c4gitlab/c4gitlab",
        ]},
        ".rule.build.common": {"rules": [{"if": f"$CI_PIPELINE_SOURCE == \"push\" && $CI_COMMIT_BRANCH"}]},
        ".rule.build.rt": {"rules": [{"if": "$C4GITLAB_PROJ_TAG"}]},
        ".rule.deploy.any": {"rules": [{"when": "manual", "if": f"$C4GITLAB_AGGR"}]},
        ".rule.deploy.prod":
            {"rules": [{"when": "manual", "if": f"$C4GITLAB_AGGR && $CI_COMMIT_TAG =~ /\\/release\\//"}]},
        ".rule.env.start": {"rules": [{"if": f"$C4GITLAB_ENV_NAME"}]},
        ".rule.env.stop": {"rules": [{"when": "manual", "if": f"$C4GITLAB_ENV_NAME"}]},
        ".build_common": {"extends": ".handler", "variables": {"GIT_DEPTH": 10}},
        "build common": {
            "extends": [".build_common", ".rule.build.common"], "script": ["c4gitlab measure build_common"],
            "stage": "develop"
        },
        ".common_job": {
            "extends": ".handler", "image": "$C4GITLAB_IMAGE", "variables": {"GIT_STRATEGY": "none"}, "needs": []
        },
        "build rt": {
            "extends": [".common_job", ".rule.build.rt"], "script": ["c4gitlab measure build_rt"], "stage": "develop",
        },
        **{
            key: value
            for op, env_mask, caption_mask in conf if op == "C4DEPLOY"
            for key, value in get_deploy_jobs(env_mask, caption_mask)
        },
        "start": {
            "extends": [".common_job", ".rule.env.start"], "script": ["c4gitlab measure start"], "stage": "start",
            "environment": {"name": env_gr_name, "action": "start", "on_stop": "stop", "url": "$C4GITLAB_ENV_URL"}
        },
        "stop": {
            "extends": [".common_job", ".rule.env.stop"], "script": ["c4gitlab measure stop"], "stage": "stop",
            "environment": {"name": env_gr_name, "action": "stop"}
        },
    }
    dump({"stages": stages, **jobs}, sys.stdout, sort_keys=True, indent=4)


def ci_run_out(cmd):
    with tempfile.TemporaryDirectory() as temp_root:
        out_path = f"{temp_root}/out.json"
        run((*cmd, "--out", out_path))
        return loads(read_text(out_path))


def handle_build_common():
    build_common_res = ci_run_out(("c4build_common", "--context", e["CI_PROJECT_DIR"]))
    image = build_common_res["image"]
    conf = 'C4GITLAB_CONFIG_JSON'  # this line will be preprocessed
    branch = e["CI_COMMIT_BRANCH"]
    tag_name_by_aggr = {
        aggr: "t4/"+branch+"."+e["CI_COMMIT_SHORT_SHA"]+"."+aggr
        for op, aggr, cond in conf if op == "C4AGGR_COND" and search(cond, branch)
    }
    tag_name_by_proj_tag = {
        tag: tag_name_by_aggr[aggr] for op, tag, aggr in conf if op == "C4TAG_AGGR" and aggr in tag_name_by_aggr
    }
    subj = sub("[^a-z]", "", branch.rpartition("/")[-1].lower())
    conn_url = connect()
    for aggr, tag_name in sorted_items(tag_name_by_aggr):
        need_tag(conn_url, tag_name)
        post_pipeline(conn_url, tag_name, image, {"C4GITLAB_AGGR": aggr})
    for tag, tag_name in sorted_items(tag_name_by_proj_tag):
        post_pipeline(conn_url, tag_name, image, {"C4GITLAB_PROJ_TAG": tag, "C4GITLAB_SUBJ": subj})


def handle_deploy(env_mask):
    user = sub("[^a-z]", "", e["GITLAB_USER_LOGIN"].lower())
    env_base = env_mask.replace("{C4SUBJ}", e["C4GITLAB_SUBJ"]).replace("{C4USER}", user)+"-"+e["C4GITLAB_AGGR"]
    env_name = f"{env_base}-env"
    env_comp, = ci_run_out(("c4ci_info", "--names", dumps([env_name])))
    group = env_comp["ci:env_group"]
    part_comps = ci_run_out(("c4ci_info", "--names", dumps(env_comp["parts"])))
    env_url = next(sorted(f"https://{h}" for c in part_comps for h in [c.get("ci:hostname")] if h), "")
    conn_url = connect()
    variables = {"C4GITLAB_ENV_GROUP": group, "C4GITLAB_ENV_NAME": env_name, "C4GITLAB_ENV_URL": env_url}
    pipeline_id = post_pipeline(conn_url, e["CI_COMMIT_TAG"], e["C4GITLAB_IMAGE"], variables)
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
    inner_cmd = (e["C4PYTHON"], "-u", script, "inner", *args)
    with subprocess.Popen(inner_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
        for line in proc.stdout:
            print(f"{str(int(monotonic()-started)).zfill(5)} {line}", end="")
        proc.wait()
    sys.exit(proc.returncode)


def main(script, op, *args):
    {
        "generate": handle_generate,
        "measure": lambda *a: handle_measure(script, *a),
        "build_common": handle_build_common,
        "build_rt": lambda: run(("c4build_rt", e["C4GITLAB_PROJ_TAG"])),
        "confirm": lambda: (),
        "deploy": handle_deploy,
        "start": lambda: run(("c4ci_up", e["C4GITLAB_ENV_NAME"])),
        "stop": lambda: run(("c4ci_down", e["C4GITLAB_ENV_NAME"])),
    }[op](*args)


main(*sys.argv)


# m, s = divmod(int(time.monotonic()-started), 60)
# def optional_job(name): return { "job":name, "optional":True }
# python3 c4proto/gitlab.py generate < c4dep.main.json > gitlab-ci-generated.0.yml
# docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab.py
