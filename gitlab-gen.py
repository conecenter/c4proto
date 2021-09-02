
import os
import json
import re

### util

def read_json(path):
  with open(path,'r') as f:
    return json.load(f)

def write_json(path, value):
  with open(path,"w") as f:
    json.dump(value, f, sort_keys=True, indent=4)

def group_map(l,f):
  res = {}
  for it in l:
    k,v = f(it)
    if k not in res: res[k] = []
    res[k].append(v)
  return res

def one(it): return it

def ext(f): return lambda arg: f(*arg)

###

def docker_conf(): return "python3 $C4CI_PROTO_DIR/gitlab-docker-conf.py"
def prod(arg):
  return [docker_conf(),f"ssh-agent perl $C4CI_PROTO_DIR/prod.pl {arg}"]
def handle(arg):
  return f"python3 $C4CI_PROTO_DIR/gitlab-ci.py {arg}"
def push_rule(cond):
  return { "if": f"$CI_PIPELINE_SOURCE == \"push\" && {cond}" }
def common_job(cond,when,stage,script):
  return {
    "rules": [{ **push_rule(cond), "when": when }],
    "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none" },
    "stage": stage, "script": script
  }
def build_path(fn):
  dir = os.environ["C4CI_BUILD_DIR"]
  return f"{dir}/{fn}"
def prefix_cond(v): return f"=~ /^{v}\\//"
def get_aggr_cond(aggr_cond_list):
  aggr_to_cond_list = group_map(aggr_cond_list, ext(lambda aggr, cond: (aggr,cond)))
  aggr_to_cond = { aggr: one(*cond_list) for aggr, cond_list in aggr_to_cond_list.items() }
  return (aggr_cond_list, aggr_to_cond)

build_common_name = "build common"
build_gate_name = "build gate"
build_frp_name = "build frp"
def build_aggr_name(v): return f"{v}.aggr"
def build_rt_name(v): return f"b {v}.rt"
stage_build_rt = "develop"
stage_deploy_de = "develop"
stage_confirm = "confirm"
stage_deploy_sp = "confirm"
stage_deploy_cl = "deploy"

def get_build_jobs(config_statements):
  def build(cond,stage,arg):
    return common_job(f"$CI_COMMIT_BRANCH {cond}","on_success",stage,prod(arg))
  def build_main(cond,arg):
    return { **build(cond,"build_main",arg), "needs": [build_common_name] }
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  aggr_to_tags = group_map(tag_aggr_list, ext(lambda tag, aggr: (aggr,tag)))
  aggr_jobs = {
    build_aggr_name(aggr): build_main(
      prefix_cond(cond), f"ci_build_aggr {aggr} " + ":".join(aggr_to_tags[aggr])
    ) for aggr, cond in aggr_cond_list
  }
  fin_jobs = {
    build_rt_name(tag): build(
      prefix_cond(aggr_to_cond[aggr]), stage_build_rt, f"ci_build {tag} {aggr}"
    ) for tag, aggr in tag_aggr_list
  }
  return {
    build_gate_name: build_main("","ci_build def"),
    build_frp_name: build_main("","ci_build_frp"),
    **aggr_jobs, **fin_jobs
  }

def get_deploy_jobs(config_statements):
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  cond_to_aggr = group_map(aggr_cond_list, ext(lambda aggr, cond: (cond,aggr)))
  cond_to_tags = group_map(tag_aggr_list, ext(lambda tag, aggr: (aggr_to_cond[aggr], tag)))
  cond_list = sorted(set(cond for aggr, cond in aggr_cond_list))
  def needs_de(cond): return [build_gate_name,build_frp_name] + ([] if cond == "nil" else [build_aggr_name(aggr) for aggr in cond_to_aggr[cond]])
  def needs_rt(cond): return [build_gate_name] + [build_rt_name(tag) for tag in cond_to_tags[cond]]
  return {
    key: value
    for env_mask, caption_mask in config_statements["C4DEPLOY"]
    for mode_mask, arg, proj_mask, opt in [re.findall(r'[^\-]+',env_mask)]
    for proj_name in (cond_list if proj_mask == "$C4PROJ" else [proj_mask])
    for cond_re in ["" if proj_name == "nil" else prefix_cond(f"{proj_name}\/release" if arg == "prod" else proj_name)]
    for cond in [f"$CI_COMMIT_BRANCH {cond_re}"]
    for mode, stage, needs_fun in (
      [(mode_mask,stage_deploy_de,needs_de)] if mode_mask == "de" else
      [(mode_mask,stage_deploy_cl,needs_rt)] if mode_mask == "cl" else
      [(mode_mask,stage_deploy_sp,needs_rt)]
    )
    for key_mask in [caption_mask.replace("$C4PROJ",proj_name)]
    for script in [[
      "export C4SUBJ=$(perl -e 's{[^\w/]}{}g,/(\w+)$/&&print$1 for $ENV{CI_COMMIT_BRANCH}')",
      "export C4USER=$(perl -e 's{[^\w/]}{}g,/(\w+)$/&&print$1 for $ENV{GITLAB_USER_LOGIN}')",
      "env | grep C4 | sort",
      handle(f"deploy {mode}-{arg}-{proj_name}-{opt} $CI_COMMIT_BRANCH")
    ]]
    for confirm_key in [key_mask.replace("$C4CONFIRM","confirm")]
    for key, value in (
      [
        (confirm_key, { **common_job(cond,"manual",stage_confirm,["echo confirming"]), "needs": [] }),
        (key_mask.replace("$C4CONFIRM","deploy"), { **common_job(cond,"manual",stage,script), "needs": [confirm_key] + needs_fun(proj_name) }),
      ] if confirm_key != key_mask else [
        (key_mask, { **common_job(cond,"manual",stage,script), "needs": needs_fun(proj_name) }),
      ]
    )
  }

def get_env_jobs():
  cond_qa = "$CI_COMMIT_TAG =~ /\\/qa-/"
  def stop(cond,when,needs): return {
    **common_job(cond,when,"stop",[handle("down")]), "needs": needs,
    "environment": { "name": "$CI_COMMIT_TAG", "action": "stop" }
  }
  start_name = "start"
  testing_name = "testing"
  return {
    start_name: {
      **common_job("$CI_COMMIT_TAG","on_success","start",[docker_conf(),handle("up $CI_ENVIRONMENT_SLUG")]),
      "environment": { "name": "$CI_COMMIT_TAG", "action": "start", "on_stop": "stop" }
    },
    testing_name: common_job(cond_qa,"on_success","testing",[handle("qa_run /c4/qa")]),
    "check": common_job("$CI_COMMIT_TAG","on_success","check",[handle("check")]),
    "stop": stop("$CI_COMMIT_TAG","manual",[start_name]),
    "auto-stop": stop(cond_qa,"on_success",[testing_name]),
    "forward": common_job("$CI_COMMIT_TAG =~ /\\/de-/","manual","start",[
      handle("deploy fc-$(perl -e '/\bde-(\w+-\w+-\w+)$/&&print$1 for $ENV{CI_COMMIT_TAG}') no-branch")
    ])
  }

def main():
  config_statements = group_map(read_json(build_path("c4dep.main.json")), lambda it: (it[0],it[1:]))
  out = {
    "variables": { "C4CI_DOCKER_CONFIG": "/tmp/c4-docker-config" },
    "stages": ["build_replink","build_common","build_main","develop","confirm","deploy","start","check","testing","stop"],
    "build_replink": {
      "image": {
        "name": "gcr.io/kaniko-project/executor:debug",
        "entrypoint": [ "" ]
      },
      "rules": [{"if":"$C4CI_REPLINK"}],
      "stage": "build_replink",
      "variables": {
        "C4DOCKER_CONF": '{"auths":{"$CI_REGISTRY":{"username":"$CI_REGISTRY_USER","password":"$CI_REGISTRY_PASSWORD"}}}'
      },
      "script": [
        "mkdir -p /kaniko/.docker",
        "echo $C4DOCKER_CONF > /kaniko/.docker/config.json",
        "cat /kaniko/.docker/config.json",
        "/kaniko/executor --context $CI_PROJECT_DIR/replink_extra --dockerfile $CI_PROJECT_DIR/replink_extra/Dockerfile --destination $CI_REGISTRY_IMAGE/replink:v2sshk3"
      ]
    },
    build_common_name: {
      "rules": [push_rule("$CI_COMMIT_BRANCH")],
      "stage": "build_common",
      "image": "$CI_REGISTRY_IMAGE/replink:v2sshk3",
      "script": [
        "export C4CI_BUILD_DIR=$CI_PROJECT_DIR",
        "export C4CI_PROTO_DIR=$C4COMMON_PROTO_DIR",
        "C4REPO_MAIN_CONF=$CI_PROJECT_DIR/c4dep.ci.replink /replink.pl",
        "C4CI_BUILD_DIR=$C4CI_PROTO_DIR C4REPO_MAIN_CONF=$C4CI_PROTO_DIR/c4dep.main.replink /replink.pl",
        *prod("ci_build_common"),
      ],
    },
    **get_build_jobs(config_statements), **get_deploy_jobs(config_statements), **get_env_jobs()
  }
  write_json(build_path("gitlab-ci-generated.yml"), out)

main()

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py