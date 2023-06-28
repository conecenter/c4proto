#!/usr/bin/python3 -u

###############
# This script is self-contained implementation of gitlab integration with an abstract target project.
# So deploy to dynamic number of environments can be done in a few clicks without job/pipeline arguments manual editing.
# Command `./gitlab.py generate $TO_REPO $BRANCH < $CONF`
# will generate gitlab config (that can be included into the target project's config) and push it to the given repo.
# $CONF is json array with 2 kind of statements:
#    ["C4AGGR_COND", "my-subproject", "^my-branch-re-"],
#    ["C4DEPLOY","sandbox-{C4USER}-{C4AGGR}=main","sandbox personal"],
# Also the target project should provide "image" for ".handler" job.
# This image should contain executables: `c4ci_prep` and `c4ci_up`.
# `c4ci_prep` is responsible for building/pushing images and making kubernetes manifests.
# `c4ci_up` is responsible for applying manifests to the cluster.
# When `my-branch-re-*` is pushed, additional pipeline will be created with  manual deploy-jobs for "my-subproject".
# When user Bob will activate "sandbox personal" job, gitlab.py will run command like:
# c4ci_prep --context $CI_PROJECT_DIR --c4env sandbox-bob-my-subproject --state main --info-out temp123-install.json
# `c4ci_prep` generates installation-json `temp123-install.json` like:
# { "c4env": "sandbox-bob-my-subproject", "state": "tp-12345678-main", "kube-context": "dev", "manifests": [...] }
# where environment `sandbox-bob-my-subproject`
# will go to the `main` operational state with images built from commit `12345678` of the target project.
# Then pipeline will be created with start/stop environment jobs for this deploy, and installation-json passed to it.
# Gitlab environment name is also defined by every manifest label "c4env_group".
# Environment URL can be taken from "Ingress" "tls" settings.
# Start-job will run command like `c4ci_up < temp123-install.json`
# `c4ci_up` will apply "manifests" and can remember them in history with "state" in title.
# Stop-job will run the same `c4ci_up` with json, where some keys are void-ed:
# { "c4env": "sandbox-bob-my-subproject", "state": "c4-off", "kube-context": "dev", "manifests": [] }
###############

import base64
from re import search, sub
import sys
from os import environ as e
from json import dumps, loads, load
import subprocess
from pathlib import Path
import http.client
from tempfile import TemporaryDirectory
from time import sleep, monotonic


def debug_args(hint, args):
    print(hint+" "+" ".join(args), file=sys.stderr)
    return args


def run(args, **opt): return subprocess.run(debug_args("running:", args), check=True, **opt)


def run_no_die(args, **opt): return subprocess.run(debug_args("running:", args), **opt).returncode == 0


def git_add(context, repo, branch, message):
    if not Path(context).exists() or Path(f"{context}/.git").exists():
        raise Exception("bad context")
    with TemporaryDirectory() as temp_root:
        if run_no_die(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=temp_root):
            run(("mv", f"{temp_root}/.git", context))
        else:
            run(("git", "init"), cwd=context)
            run(("git", "remote", "add", "origin", repo), cwd=context)
            run(("git", "checkout", "-b", branch), cwd=context)
    run(("git", "add", "."), cwd=context)
    run(("git", "config", "user.email", "ci@c4proto"), cwd=context)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=context)
    if run_no_die(("git", "commit", "-m", message), cwd=context):
        run(("git", "push", "--set-upstream", "origin", branch), cwd=context)
    elif len(run(("git", "status", "--porcelain=v1"), cwd=context, text=True, capture_output=True).stdout.strip()) > 0:
        raise Exception("can not commit")
    else:
        print("unchanged")


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
    debug_args("need_tag", (status, res))


def post_pipeline(conn_url, tag_name, variables):
    variables_list = [{"key": k, "value": v} for k, v in sorted(variables.items())]
    status, res = exchange(conn_url, "POST", "pipeline", {"ref": tag_name, "variables": variables_list})
    if status == 201:
        pipeline_id, pipeline_web_url = debug_args(f"pipeline created:", (res["id"], res["web_url"]))
        return pipeline_id
    raise Exception((status, res))


def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')


def over_bytes(f, text): return f(text.encode('utf-8')).decode('utf-8')


def handle_generate(repo, branch):
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
    temp_root = TemporaryDirectory()
    res = dumps(out, sort_keys=True, indent=4)
    Path(f"{temp_root}/gitlab-ci-generated.yml").write_text(res, encoding='utf-8', errors='strict')
    git_add(temp_root, repo, branch, "gitlab conf generated")


def handle_init():
    conf = 'C4GITLAB_CONFIG_JSON'  # this line will be preprocessed
    branch = e["CI_COMMIT_BRANCH"]
    tag_name_by_aggr = {
        aggr: "t4/"+branch+"."+e["CI_COMMIT_SHORT_SHA"]+"."+aggr
        for op, aggr, cond in conf if op == "C4AGGR_COND" and search(cond, branch)
    }
    subj = sub("[^a-z]", "", branch.rpartition("/")[-1].lower())
    conn_url = connect()
    for aggr, tag_name in sorted(tag_name_by_aggr.items()):
        need_tag(conn_url, tag_name)
        post_pipeline(conn_url, tag_name, {"C4GITLAB_AGGR": aggr, "C4GITLAB_SUBJ": subj})


def handle_deploy(env_state_mask):
    env_mask, state = env_state_mask.split("=")
    c4env = (
        env_mask.replace("{C4USER}", sub("[^a-z]", "", e["GITLAB_USER_LOGIN"].lower()))
        .replace("{C4SUBJ}", e["C4GITLAB_SUBJ"]).replace("{C4AGGR}", e["C4GITLAB_AGGR"])
    )
    temp_root = TemporaryDirectory()
    out_path = f"{temp_root.name}/out.json"
    run(("c4ci_prep", "--context", e["CI_PROJECT_DIR"], "--c4env", c4env, "--state", state, "--info-out", out_path))
    start_info_raw = read_text(out_path)
    info = loads(start_info_raw)
    stop_info_raw = dumps({**info, "state": "off", "manifests": []}, sort_keys=True)
    conn_url = connect()
    mans, name = info["manifests"], info["c4env"]
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
        job_status, job_web_url = debug_args("env job:", (job["status"], job["web_url"]))
        if job_status == "success":
            break
        if job_status == "failed" or job_status == "canceled":
            raise Exception(job_status)
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
