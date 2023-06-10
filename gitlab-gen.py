
import json
import base64
from c4util import group_map, one, read_json

### util


def ext(f): return lambda arg: f(*arg)

###

def docker_conf(): return call("C4GITLAB_DOCKER_CONF_BODY")+" > $C4CI_DOCKER_CONFIG"
def call(var): return f"echo ${var} | base64 -d | python3 -u"
def define(lines): return base64.b64encode("\n".join(lines).encode('utf-8')).decode('utf-8')

def push_rule(cond):
  return { "if": f"$CI_PIPELINE_SOURCE == \"push\" && {cond}" }
def common_job(cond,when,stage,needs,script):
  return {
    "rules": [{ **push_rule(cond), "when": when }],
    "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none" },
    "stage": stage, "needs": needs, "script": script
  }

def esc_slashes(v): return v.replace("/","\\/")
def prefix_cond(v):
    return f"$CI_COMMIT_BRANCH =~ /{esc_slashes(v)}/" if v else "$CI_COMMIT_BRANCH"

def get_aggr_cond(aggr_cond_list):
  aggr_to_cond_list = group_map(aggr_cond_list, ext(lambda aggr, cond: (aggr,cond)))
  aggr_to_cond = { aggr: one(*cond_list) for aggr, cond_list in aggr_to_cond_list.items() }
  return (aggr_cond_list, aggr_to_cond)

build_common_name = "build common"
build_gate_name = "build gate"
def build_rt_name(tag,aggr): return f"b {tag} {aggr} rt"
stage_deploy_de = "develop"
stage_confirm = "confirm"
stage_deploy_sp = "confirm"
stage_deploy_cl = "deploy"


def get_build_jobs(config_statements):
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  return {
    build_gate_name: common_job(
      prefix_cond(""), "on_success", "build_main", [build_common_name],
      [docker_conf(), f"c4ci build --proj-tag def --push-secret $C4CI_DOCKER_CONFIG"]
    ),
    **{
      build_rt_name(tag,aggr): common_job(
        prefix_cond(aggr_to_cond[aggr]), "on_success", "build_main", [build_common_name],
        [docker_conf(), f"c4ci build --proj-tag {tag} --push-secret $C4CI_DOCKER_CONFIG"]
      ) for tag, aggr in tag_aggr_list
    }
  }

# build aggr jobs -- C4AGGR_COND
# build fin jobs -- C4TAG_AGGR
# deploy jobs -- C4DEPLOY > C4TAG_AGGR

#def optional_job(name): return { "job":name, "optional":True }
def get_deploy_jobs(config_statements):
  return {
    key: value
    for env_mask, caption_mask in config_statements["C4DEPLOY"]
    for proj_sub, cond_pre in config_statements["C4AGGR_COND"] if cond_pre or env_mask.startswith("de-")
    for cond in [
      prefix_cond(cond_pre)+" && "+prefix_cond("/release/") if env_mask == "cl-prod" else
      prefix_cond(cond_pre)
    ]
    for stage in (
      [stage_deploy_de] if env_mask.startswith("de-") else
      [stage_deploy_cl] if env_mask.startswith("cl-") else
      [stage_deploy_sp]
    )
    for key_mask in [caption_mask.replace("$C4PROJ_SUB",proj_sub)]
    for script in [[
      docker_conf(),
      "export C4SUBJ=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{CI_COMMIT_BRANCH}')",
      "export C4USER=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{GITLAB_USER_LOGIN}')",
      f"export C4CI_ENV_NAME={env_mask}-{proj_sub}-env",
      "c4ci ci_wait_images $C4CI_ENV_NAME",
      "c4ci ci_push $C4CI_ENV_NAME",
      f"c4ci ci_get /tmp/c4ci-env_group $C4CI_ENV_NAME/ci:env_group /tmp/c4ci-hostname {env_mask}-{proj_sub}-gate/ci:hostname",
      "export C4CI_ENV_GROUP=$(cat /tmp/c4ci-env_group)",
      "export C4CI_ENV_URL=https://$(cat /tmp/c4ci-hostname)",
      call("C4GITLAB_DEPLOY_BODY"),
      f"c4ci ci_check_images $C4CI_ENV_NAME",
    ]]
    for confirm_key in [key_mask.replace("$C4CONFIRM","confirm")]
    for key, value in (
      [
        (confirm_key, common_job(cond,"manual",stage_confirm,[],["echo confirming"])),
        (key_mask.replace("$C4CONFIRM","deploy"), common_job(cond,"manual",stage,[confirm_key,build_common_name],script)),
      ] if confirm_key != key_mask else [
        (key_mask, common_job(cond,"manual",stage,[build_common_name],script)),
      ]
    )
  }


def env_job(when, stage, action, add_env, ci_act):
    return {
        "rules": [{"if": f"$CI_PIPELINE_SOURCE == \"api\" && $C4CI_ENV_NAME", "when": when}],
        "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none"},
        "stage": stage, "needs": [], "script": [f"c4ci {ci_act} $C4CI_ENV_NAME"],
        "environment": {"name": "$C4CI_ENV_GROUP/$C4CI_ENV_NAME", "url": "$C4CI_ENV_URL", "action": action, **add_env}
    }
def get_env_jobs():
    return {
        "start": env_job("on_success", "start", "start", {"on_stop": "stop"}, "ci_up"),
        "stop": env_job("manual", "stop", "stop", {}, "ci_down")
    }

def replink(dir,fn):
  return f"C4CI_BUILD_DIR={dir} C4REPO_MAIN_CONF={dir}/{fn} /replink.pl"


def main():
  config_statements = group_map(read_json("/dev/stdin"), lambda it: (it[0], it[1:]))
  out = {
    "variables": {
      "C4CI_DOCKER_CONFIG": "/tmp/c4-docker-config",
      "GIT_DEPTH": 10,
      "C4GITLAB_DOCKER_CONF_BODY": define((
        'from json import dumps as d',
        'from os import environ as e',
        'print(d({"auths":{e["CI_REGISTRY"]:{"username":e["CI_REGISTRY_USER"],"password":e["CI_REGISTRY_PASSWORD"]}}}))'
      )),
      "C4GITLAB_DEPLOY_BODY": define((
        'from os import environ as e',
        'import gitlab',
        'tag_name = "t4/"+e["CI_COMMIT_BRANCH"]+"."+e["CI_COMMIT_SHORT_SHA"]',
        'project = gitlab.Gitlab(e["CI_SERVER_URL"], private_token=e["C4CI_TOKEN"]).projects.get(e["CI_PROJECT_ID"])',
        'try: project.tags.get(tag_name)',
        'except gitlab.GitlabGetError: project.tags.create({"tag_name": tag_name, "ref": e["CI_COMMIT_SHA"]})',
        'args = ("C4CI_ENV_GROUP","C4CI_ENV_NAME","C4CI_ENV_URL")',
        'project.pipelines.create({"ref": tag_name, "variables": [{"key": k, "value": e[k]} for k in args]})',
      )),
    },
    "stages": ["build_common", "build_main", "develop", "confirm", "deploy", "start", "stop"],
    build_common_name: {
      "extends": [".build_common"], "rules": [push_rule(prefix_cond(""))], "stage": "build_common",
      "image": "$C4COMMON_BUILDER_IMAGE", "script": [docker_conf(), "$C4COMMON_BUILDER_CMD"],
    },
    **get_build_jobs(config_statements), **get_deploy_jobs(config_statements), **get_env_jobs()
  }
  print(json.dumps(out, sort_keys=True, indent=4))


main()

# python3 c4proto/gitlab-gen.py < c4dep.main.json > gitlab-ci-generated.0.yml

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py