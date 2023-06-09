
import sys
import json
from c4util import group_map, one, read_json, changing_text

### util


def ext(f): return lambda arg: f(*arg)

###

def docker_conf(): return f"python3 $C4CI_PROTO_DIR/gitlab-docker-conf.py"
def handle(arg):
  return f"python3 $C4CI_PROTO_DIR/gitlab-ci.py {arg}"
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

def build_remote(python,args):
  return [
    docker_conf(),
    f"{python} -u $C4CI_PROTO_DIR/run_with_prefix.py time {python} -u $C4CI_PROTO_DIR/build_remote.py {args}"
  ]

def get_build_jobs(config_statements):
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  add_args = " --context $C4CI_BUILD_DIR --image $C4COMMON_IMAGE --push-secret $C4CI_DOCKER_CONFIG --java-options \"$C4BUILD_JAVA_TOOL_OPTIONS\" "
  return {
    "rebuild": common_job(
      prefix_cond(""),"manual","build_main",[build_common_name],
      [handle(f"rebuild $CI_COMMIT_BRANCH")]
    ),
    build_gate_name: common_job(
      prefix_cond(""), "on_success", "build_main", [build_common_name],
      build_remote("python3.8", f"build_gate {add_args}")
    ),
    **{
      build_rt_name(tag,aggr): common_job(
        prefix_cond(aggr_to_cond[aggr]), "on_success", "build_main", [build_common_name],
        build_remote("python3.8", f"build_rt --commit $CI_COMMIT_SHORT_SHA --proj-tag {tag} --build-client '1' {add_args}")
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
      "export C4SUBJ=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{CI_COMMIT_BRANCH}')",
      "export C4USER=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{GITLAB_USER_LOGIN}')",
      "env | grep C4 | sort",
      handle(f"deploy {env_mask}-{proj_sub} $CI_COMMIT_BRANCH")
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


def env_job(when, stage, action, add_env, script):
    return {
        "rules": [{"if": f"$CI_PIPELINE_SOURCE == \"api\" && $C4CI_ENV_NAME", "when": when}],
        "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none"},
        "stage": stage, "needs": [], "script": script,
        "environment": {
            "name": "$C4CI_ENV_GROUP/$C4CI_ENV_NAME", "environment_url": "$C4CI_ENV_URL", "action": action, **add_env
        }
    }
def get_env_jobs():
    return {
        "start": env_job("on_success", "start", "start", {"on_stop": "stop"}, [
            docker_conf(), "export C4COMMIT=$CI_COMMIT_SHORT_SHA", handle("up")
        ]),
        "stop": env_job("manual", "stop", "stop", {}, [handle("down")])
    }

def replink(dir,fn):
  return f"C4CI_BUILD_DIR={dir} C4REPO_MAIN_CONF={dir}/{fn} /replink.pl"

def main(build_path):
  config_statements = group_map(read_json(f"{build_path}/c4dep.main.json"), lambda it: (it[0],it[1:]))
  out = {
    "variables": { "C4CI_DOCKER_CONFIG": "/tmp/c4-docker-config", "GIT_DEPTH": 10 },
    "stages": ["build_common","build_main","develop","confirm","deploy","start","check","testing","stop"],
    build_common_name: {
      "rules": [push_rule(prefix_cond(""))], "stage": "build_common",
      "image": "ghcr.io/conecenter/c4replink:v3kc",
      "script": [
        "date", replink("$CI_PROJECT_DIR","c4dep.ci.replink"),
        "date", replink("$C4COMMON_PROTO_DIR","c4dep.main.replink"),
        "date", "export C4CI_PROTO_DIR=$C4COMMON_PROTO_DIR",
        *build_remote(
          "python3",
          f"build_common --build-dir $C4CI_BUILD_DIR --push-secret $C4CI_DOCKER_CONFIG " +
          f" --context $CI_PROJECT_DIR --image $C4COMMON_IMAGE --commit $CI_COMMIT_SHORT_SHA"
        )
      ],
    },
    **get_build_jobs(config_statements), **get_deploy_jobs(config_statements), **get_env_jobs()
  }
  changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4))

main(*sys.argv[1:])

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py