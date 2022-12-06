
import sys
import json
from c4util import group_map, one, read_json, read_text, changing_text

### util


def ext(f): return lambda arg: f(*arg)

###

def docker_conf(proto_dir): return f"python3 {proto_dir}/gitlab-docker-conf.py"
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

build_proto_name = "build proto"
build_common_name = "build common"
build_gate_name = "build gate"
def build_rt_name(tag,aggr): return f"b {tag} {aggr} rt"
stage_deploy_de = "develop"
stage_confirm = "confirm"
stage_deploy_sp = "confirm"
stage_deploy_cl = "deploy"

def build_remote(python,proto_dir,args):
  return f"{python} -u {proto_dir}/run_with_timestamps.py {python} -u {proto_dir}/build_remote.py {args}"
def build_rt_script(commit,image,tag,context,build_client):
  proto_dir = "$C4CI_PROTO_DIR"
  return [docker_conf(proto_dir), build_remote(
    "python3.8", proto_dir,
    f"build_rt --commit {commit} --image {image} --proj-tag {tag} --context {context} --build-client '{build_client}'"+
    " --push-secret $C4CI_DOCKER_CONFIG --java-options \"$C4BUILD_JAVA_TOOL_OPTIONS\" "
  )]

def get_build_jobs(config_statements):
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  return {
    "rebuild": common_job(
      prefix_cond(""),"manual","build_main",[build_common_name],
      [handle(f"rebuild $CI_COMMIT_BRANCH")]
    ),
    **{
      build_rt_name(tag,aggr): common_job(
        prefix_cond(aggr_to_cond[aggr]), "on_success", "build_main", [build_common_name],
        build_rt_script("$CI_COMMIT_SHORT_SHA", "$C4COMMON_IMAGE", tag, "$C4CI_BUILD_DIR", "1")
      ) for tag, aggr in tag_aggr_list
    }
  }

# build aggr jobs -- C4AGGR_COND
# build fin jobs -- C4TAG_AGGR
# deploy jobs -- C4DEPLOY > C4TAG_AGGR

def get_deploy_jobs(config_statements):
  def optional_job(name): return { "job":name, "optional":True }
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  aggr_cond_list = config_statements["C4AGGR_COND"]
  needs_rt = [optional_job(build_rt_name(tag,aggr)) for tag, aggr in tag_aggr_list]
  needs_de = [build_common_name]
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
        docker_conf("$C4CI_PROTO_DIR"), "export C4COMMIT=$CI_COMMIT_SHORT_SHA", handle("up $CI_ENVIRONMENT_SLUG")
      ]),
      "environment": { "name": "$CI_COMMIT_TAG", "action": "start", "on_stop": "stop" }
    },
    check_name: common_job("$CI_COMMIT_TAG","on_success","check",[start_name],[handle("check")]),
    testing_name: common_job(cond_qa,"on_success","testing",[check_name],[handle("qa_run /c4/qa")]),
    "stop": stop("$CI_COMMIT_TAG","manual",[start_name]),
    "auto-stop": stop(cond_qa,"on_success",[testing_name]),
  }

def main(build_path):
  config_statements = group_map(read_json(f"{build_path}/c4dep.main.json"), lambda it: (it[0],it[1:]))
  rl_fn = "c4dep.ci.replink"
  link = one(*(line for line in read_text(f"{build_path}/{rl_fn}").split("\n") if line.startswith("C4REL c4proto/")))
  commit = link.split()[-1]
  proto_image = f"$CI_REGISTRY_IMAGE:c4p.{commit}"
  replink = f"C4CI_BUILD_DIR=$CI_PROJECT_DIR C4REPO_MAIN_CONF=$CI_PROJECT_DIR/{rl_fn} /replink.pl"
  out = {
    "variables": { "C4CI_DOCKER_CONFIG": "/tmp/c4-docker-config", "GIT_DEPTH": 10 },
    "stages": ["build_common","build_main","develop","confirm","deploy","start","check","testing","stop"],
    build_proto_name: {
      "rules": [{ **push_rule(prefix_cond("")), "when": "manual" }], "stage": "build_common",
      "image": "ghcr.io/conecenter/c4replink:v3k", "variables": {"GIT_STRATEGY": "none" },
      "script": [
        f"echo '{link}' > $CI_PROJECT_DIR/{rl_fn}",
        replink, docker_conf("$C4COMMON_PROTO_DIR"),
        build_remote("python3", "$C4COMMON_PROTO_DIR",
          f"build_proto --build-dir $C4CI_BUILD_DIR --image {proto_image} --push-secret $C4CI_DOCKER_CONFIG --context $C4COMMON_PROTO_DIR"
        )
      ]
    },
    build_gate_name: {
      "rules": [push_rule(prefix_cond(""))], "stage": "build_common", "needs": [build_proto_name],
      "image": proto_image, "variables": {"GIT_STRATEGY": "none" },
      "script": build_rt_script(commit, proto_image, "def", "$C4CI_PROTO_DIR", ""),
    },
    build_common_name: {
      "rules": [push_rule(prefix_cond(""))], "stage": "build_common",
      "image": proto_image,
      "script": [
        replink,
        build_remote("python3.8", "$C4CI_PROTO_DIR",
          "build_common --user $CI_REGISTRY_USER --password $CI_REGISTRY_PASSWORD --registry $CI_REGISTRY" +
          f" --context $CI_PROJECT_DIR --base-image {proto_image} --image $C4COMMON_IMAGE --build-dir $C4CI_BUILD_DIR"
        )
      ],
    },
    **get_build_jobs(config_statements), **get_deploy_jobs(config_statements), **get_env_jobs()
  }
  changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4), None)

main(*sys.argv[1:])

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py