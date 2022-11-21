
import os
import json
from c4util import group_map, one, read_json

### util

def write_json(path, value):
  with open(path,"w") as f:
    json.dump(value, f, sort_keys=True, indent=4)

def ext(f): return lambda arg: f(*arg)

###

def docker_conf(): return "python3 $C4CI_PROTO_DIR/gitlab-docker-conf.py"
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
def build_path(fn):
  dir = os.environ["C4CI_BUILD_DIR"]
  return f"{dir}/{fn}"
def esc_slashes(v): return v.replace("/","\\/")
def prefix_cond(v):
    return f"$CI_COMMIT_BRANCH =~ /{esc_slashes(v)}/" if v else "$CI_COMMIT_BRANCH"

def get_aggr_cond(aggr_cond_list):
  aggr_to_cond_list = group_map(aggr_cond_list, ext(lambda aggr, cond: (aggr,cond)))
  aggr_to_cond = { aggr: one(*cond_list) for aggr, cond_list in aggr_to_cond_list.items() }
  return (aggr_cond_list, aggr_to_cond)

build_common_name = "build common"
build_gate_name = "build gate"
build_aggr_name = "build de"
def build_rt_name(tag,aggr): return f"b {tag} {aggr} rt"
stage_deploy_de = "develop"
stage_confirm = "confirm"
stage_deploy_sp = "confirm"
stage_deploy_cl = "deploy"

def get_build_jobs(config_statements):
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  def build(cond,args):
    return common_job(prefix_cond(cond),"on_success","build_main",[build_common_name],[
      docker_conf(),
      f"python3.8 -u $C4CI_PROTO_DIR/build_remote.py {args} --image $C4COMMON_IMAGE --push-secret $C4CI_DOCKER_CONFIG"
    ])
  def build_rt(cond,tag):
    return build(cond,f"build_rt --commit $CI_COMMIT_SHORT_SHA --proj-tag {tag} --java-options \"$C4BUILD_JAVA_TOOL_OPTIONS\" ")
  return {
    "rebuild": common_job(
      prefix_cond(""),"manual","build_main",[build_common_name],
      [handle(f"rebuild $CI_COMMIT_BRANCH")]
    ),
    build_aggr_name: build("","build_de"),
    build_gate_name: build_rt("","def"),
    **{ build_rt_name(tag,aggr): build_rt(aggr_to_cond[aggr], tag) for tag, aggr in tag_aggr_list }
  }

# build aggr jobs -- C4AGGR_COND
# build fin jobs -- C4TAG_AGGR
# deploy jobs -- C4DEPLOY > C4TAG_AGGR

def get_deploy_jobs(config_statements):
  def optional_job(name): return { "job":name, "optional":True }
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  aggr_cond_list = config_statements["C4AGGR_COND"]
  needs_rt = [build_gate_name] + [optional_job(build_rt_name(tag,aggr)) for tag, aggr in tag_aggr_list]
  needs_de = [build_gate_name,build_aggr_name]
  return {
    key: value
    for env_mask, caption_mask in config_statements["C4DEPLOY"]
    for proj_sub, cond_pre in aggr_cond_list if cond_pre or env_mask.startswith("de-")
    for cond in [
      prefix_cond(cond_pre)+" && "+prefix_cond("/release/") if env_mask == "cl-prod" else
      prefix_cond(cond_pre)
    ]
    for stage, needs in (
      [(stage_deploy_de,needs_de)] if env_mask.startswith("de-") else
      [(stage_deploy_cl,needs_rt)] if env_mask.startswith("cl-") else
      [(stage_deploy_sp,needs_rt)]
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
        (key_mask.replace("$C4CONFIRM","deploy"), common_job(cond,"manual",stage,[confirm_key]+needs,script)),
      ] if confirm_key != key_mask else [
        (key_mask, common_job(cond,"manual",stage,needs,script)),
      ]
    )
  }

def get_env_jobs():
  cond_qa = "$CI_COMMIT_TAG =~ /\\/qa-/"
  def stop(cond,when,needs): return {
    **common_job(cond,when,"stop",needs,[handle("down")]),
    "environment": { "name": "$CI_COMMIT_TAG", "action": "stop" }
  }
  start_name = "start"
  check_name = "check"
  testing_name = "testing"
  return {
    start_name: {
      **common_job("$CI_COMMIT_TAG","on_success","start",[],[
        docker_conf(), "export C4COMMIT=$CI_COMMIT_SHORT_SHA", handle("up $CI_ENVIRONMENT_SLUG")
      ]),
      "environment": { "name": "$CI_COMMIT_TAG", "action": "start", "on_stop": "stop" }
    },
    check_name: common_job("$CI_COMMIT_TAG","on_success","check",[start_name],[handle("check")]),
    testing_name: common_job(cond_qa,"on_success","testing",[check_name],[handle("qa_run /c4/qa")]),
    "stop": stop("$CI_COMMIT_TAG","manual",[start_name]),
    "auto-stop": stop(cond_qa,"on_success",[testing_name]),
  }

def main():
  config_statements = group_map(read_json(build_path("c4dep.main.json")), lambda it: (it[0],it[1:]))
  out = {
    "variables": { "C4CI_DOCKER_CONFIG": "/tmp/c4-docker-config" },
    "stages": ["build_common","build_main","develop","confirm","deploy","start","check","testing","stop"],
    build_common_name: {
      "rules": [push_rule("$CI_COMMIT_BRANCH")],
      "stage": "build_common",
      "image": "ghcr.io/conecenter/c4replink:v3k",
      "script": [
        "export C4CI_BUILD_DIR=$CI_PROJECT_DIR",
        "export C4CI_PROTO_DIR=$C4COMMON_PROTO_DIR",
        "C4REPO_MAIN_CONF=$CI_PROJECT_DIR/c4dep.ci.replink /replink.pl",
        "C4CI_BUILD_DIR=$C4CI_PROTO_DIR C4REPO_MAIN_CONF=$C4CI_PROTO_DIR/c4dep.main.replink /replink.pl",
        docker_conf(),
        "mkdir -p $C4CI_BUILD_DIR/target",
        "perl $C4CI_PROTO_DIR/sync_mem.pl $C4CI_BUILD_DIR",
        "cp $C4CI_PROTO_DIR/.dockerignore $C4CI_BUILD_DIR/.dockerignore",
        "cp $C4CI_BUILD_DIR/build.def.dockerfile $C4CI_BUILD_DIR/Dockerfile",
        "python3 $C4CI_PROTO_DIR/build_remote.py build_image --context $C4CI_BUILD_DIR --image $C4COMMON_IMAGE --push-secret $C4CI_DOCKER_CONFIG",
      ],
    },
    **get_build_jobs(config_statements), **get_deploy_jobs(config_statements), **get_env_jobs()
  }
  write_json(build_path("gitlab-ci-generated.yml"), out)

main()

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py