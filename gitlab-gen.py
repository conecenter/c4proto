
import base64
from re import search, sub, match
import sys
from os import environ as e
from json import dumps, dump, loads, load
import subprocess
from pathlib import Path


def run(args, **opt):
    print("running: " + " ".join(args), file=sys.stderr)
    return subprocess.run(args, check=True, **opt)


def post(res, data):
    headers = ("-H", "PRIVATE-TOKEN: "+e["C4CI_TOKEN"], "-H", "Content-Type: application/json")
    url = e["CI_API_V4_URL"]+"/projects/"+e["CI_PROJECT_ID"]+"/"+res
    cmd = ("curl", "-X", "POST", *headers, url, "-d", dumps(data))
    run(cmd)


def write_docker_conf():
    push_secret = "/tmp/c4-docker-config"
    data = {"auths": {e["CI_REGISTRY"]: {"username": e["CI_REGISTRY_USER"], "password": e["CI_REGISTRY_PASSWORD"]}}}
    Path(push_secret).write_text(dumps(data), encoding='utf-8', errors='strict')
    return push_secret


def read_text(path): return Path(path).read_text(encoding='utf-8', errors='strict')


def get_deploy_jobs(env_mask, key_mask):
    rule = ".rule.deploy.prod" if env_mask == "cl-prod" else ".rule.deploy.any"
    deploy_stages = {"de": "develop", "sp": "confirm", "cl": "deploy"}
    confirm_key = key_mask.replace("$C4CONFIRM", "confirm")
    confirm_key_opt = [confirm_key] if confirm_key != key_mask else []
    return [
        *[(k, {
            "extends": [".common_job", rule], "script": ["c4gitlab confirm"], "stage": "confirm"
        }) for k in confirm_key_opt],
        (key_mask.replace("$C4CONFIRM", "deploy"), {
            "extends": [".common_job", rule], "script": [f"c4gitlab deploy '{env_mask}'"],
            "stage": deploy_stages[env_mask.partition("-")[0]], "needs": confirm_key_opt
        })
    ]


def handle_generate():
    conf = load(sys.stdin)
    script_body = read_text(sys.argv[0]).replace("'"+"C4GITLAB_CONFIG_JSON"+"'", dumps(conf))
    script_body_encoded = base64.b64encode(script_body.encode('utf-8')).decode('utf-8')
    stages = ["develop", "confirm", "deploy", "start", "stop"]
    cond_push = "$CI_PIPELINE_SOURCE == \"push\""
    env_gr_name = "$C4CI_ENV_GROUP/$C4CI_ENV_NAME"
    jobs = {
        ".handler": {"before_script": [
            f"echo '{script_body_encoded}' | base64 -d >> c4gitlab", "alias c4gitlab='python3.8 -u c4gitlab'"
        ]},
        ".rule.build.common": {"rules": [{"if": f"{cond_push} && $CI_COMMIT_BRANCH"}]},
        ".rule.build.rt": {"rules": [{"if": "$C4CI_BUILD_RT"}]},
        ".rule.deploy.any": {"rules": [{"when": "manual", "if": f"{cond_push} && $CI_COMMIT_TAG"}]},
        ".rule.deploy.prod": {"rules": [{"when": "manual", "if": f"{cond_push} && $CI_COMMIT_TAG =~ /\\/release\\//"}]},
        ".rule.env.start": {"rules": [{"if": f"$C4CI_ENV_NAME"}]},
        ".rule.env.stop": {"rules": [{"when": "manual", "if": f"$C4CI_ENV_NAME"}]},
        ".build_common": {"extends": ".handler", "image": "$C4COMMON_BUILDER_IMAGE", "variables": {"GIT_DEPTH": 10}},
        "build common": {
            "extends": [".build_common", ".rule.build.common"], "script": ["c4gitlab build_common"], "stage": "develop"
        },
        ".common_job": {
            "extends": ".handler", "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none"}, "needs": []
        },
        "build rt": {
            "extends": [".common_job", ".rule.build.rt"], "script": ["c4gitlab build_rt"], "stage": "develop",
        },
        **{
            key: value
            for op, env_mask, caption_mask in conf if op == "C4DEPLOY"
            for key, value in get_deploy_jobs(env_mask, caption_mask)
        },
        "start": {
            "extends": [".common_job", ".rule.env.start"], "script": ["c4gitlab start"], "stage": "start",
            "environment": {"name": env_gr_name, "action": "start", "on_stop": "stop", "url": "$C4CI_ENV_URL"}
        },
        "stop": {
            "extends": [".common_job", ".rule.env.stop"], "script": ["c4gitlab stop"], "stage": "stop",
            "environment": {"name": env_gr_name, "action": "stop"}
        },
    }
    dump({"stages": stages, **jobs}, sys.stdout, sort_keys=True, indent=4)


def handle_build_common():
    push_secret = write_docker_conf()
    run(("sh", "-c", e["C4COMMON_BUILDER_CMD"]), env={**e, "C4CI_DOCKER_CONFIG": push_secret})
    conf = 'C4GITLAB_CONFIG_JSON'
    tag_name_by_aggr = {
        aggr: "t4/"+e["CI_COMMIT_BRANCH"]+"."+e["CI_COMMIT_SHORT_SHA"]+"."+aggr
        for op, aggr, cond in conf if op == "C4AGGR_COND" and search(cond, e["CI_COMMIT_BRANCH"])
    }
    tag_name_by_proj_tag = {
        tag: tag_name_by_aggr[aggr] for op, tag, aggr in conf if op == "C4TAG_AGGR" and aggr in tag_name_by_aggr
    }
    for aggr, tag_name in sorted(tag_name_by_aggr.items()):
        post("repository/tags", {"tag_name": tag_name, "ref": e['CI_COMMIT_SHA']})
    for tag, tag_name in sorted(tag_name_by_proj_tag.items()):
        post("pipeline", {"ref": tag_name, "variables": [{"key": "C4CI_BUILD_RT", "value": tag}]})


def handle_deploy(env_mask):
    subj_raw, proj_sub = match(".+/([^/]+)\\.\\w+\\.([\\w\\-]+)", e["CI_COMMIT_TAG"]).groups
    subj = sub("\\W", "", subj_raw.lower())
    user = sub("\\W", "", e["GITLAB_USER_LOGIN"].lower())
    env_base = env_mask.replace("{C4SUBJ}", subj).replace("{C4USER}", user) + "-" + proj_sub
    env_name = f"{env_base}-env"
    group_path, hostname_path = "/tmp/c4ci-env_group", "/tmp/c4ci-hostname"
    push_secret = write_docker_conf()
    run(("c4ci", "ci_wait_images", env_name))
    run(("c4ci", "ci_push", env_name), env={**e, "C4CI_DOCKER_CONFIG": push_secret})
    run(("c4ci", "ci_get", group_path, f"{env_name}/ci:env_group", hostname_path, f"{env_base}-gate/ci:hostname"))
    post("pipeline", {"ref": e["CI_COMMIT_TAG"], "variables": [
        {"key": "C4CI_ENV_GROUP", "value": read_text(group_path)},
        {"key": "C4CI_ENV_NAME", "value": env_name},
        {"key": "C4CI_ENV_URL", "value": "https://"+read_text(hostname_path)}
    ]})
    run(("c4ci", "ci_check_images", env_name))


handle = {
    "generate": handle_generate,
    "build_common": handle_build_common,
    "build_rt": lambda: run(("c4ci", "build", "--proj-tag", e["C4CI_BUILD_RT"], "--push-secret", write_docker_conf())),
    "confirm": lambda: (),
    "deploy": handle_deploy,
    "start": lambda: run(("c4ci", "ci_up", e["C4CI_ENV_NAME"])),
    "stop": lambda: run(("c4ci", "ci_down", e["C4CI_ENV_NAME"])),
}
handle[sys.argv[1]](*sys.argv[2:])

#def optional_job(name): return { "job":name, "optional":True }

# python3 c4proto/gitlab-gen.py generate < c4dep.main.json > gitlab-ci-generated.0.yml

# docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py
