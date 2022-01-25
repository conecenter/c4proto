
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
def common_job(cond,when,stage,needs,script):
  return {
    "rules": [{ **push_rule(cond), "when": when }],
    "image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none" },
    "stage": stage, "needs": needs, "script": script
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
  def build(cond,stage,needs,arg):
    return common_job(f"$CI_COMMIT_BRANCH {cond}","on_success",stage,needs,prod(arg))
  def build_main(cond,arg):
    return build(cond,"build_main",[build_common_name],arg)
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
      prefix_cond(aggr_to_cond[aggr]), stage_build_rt, [build_aggr_name(aggr)], # sorted(aggr_jobs.keys())
      f"ci_build {tag} {aggr}"
    ) for tag, aggr in tag_aggr_list
  }
  return {
    "rebuild": common_job(
      "$CI_COMMIT_BRANCH","manual","build_main",[build_common_name],
      [handle(f"rebuild $CI_COMMIT_BRANCH")]
    ),
    build_gate_name: build_main("","ci_build def"),
    build_frp_name: build_main("","ci_build_frp"),
    **aggr_jobs, **fin_jobs
  }

# build aggr jobs -- C4AGGR_COND
# build fin jobs -- C4TAG_AGGR
# deploy jobs -- C4DEPLOY > C4TAG_AGGR

def get_deploy_jobs(config_statements):
  tag_aggr_list = config_statements["C4TAG_AGGR"]
  def tag_to_proj(tag): return re.match(r'\w+',tag)[0]
  tag_aggr_list_by_proj = group_map(tag_aggr_list, ext(lambda tag, aggr: (tag_to_proj(tag),(tag,aggr))))
  proj_list = sorted(tag_aggr_list_by_proj.keys())
  (aggr_cond_list, aggr_to_cond) = get_aggr_cond(config_statements["C4AGGR_COND"])
  def needs_de(aggr):
    return [build_gate_name,build_frp_name] + ([] if aggr == "" else [build_aggr_name(aggr)])
  def needs_rt(info):
    return [build_gate_name] + [build_rt_name(tag) for tag, aggr in info]
  return {
    key: value
    for env_mask, caption_mask in config_statements["C4DEPLOY"]
    for mode, arg, proj_mask, opt in [re.findall(r'[^\-]+',env_mask)]
    for proj_name in (proj_list if proj_mask == "$C4PROJ" else [proj_mask])
    for info in [[] if proj_name == "nil" else tag_aggr_list_by_proj[proj_name]]
    for aggr in [one(*set(aggr for tag, aggr in info)) if info else ""]
    for cond_pre in [aggr_to_cond[aggr] if aggr in aggr_to_cond else ""]
    for cond_re in ["" if cond_pre == "" else prefix_cond(f"{cond_pre}\/release" if arg == "prod" else cond_pre)]
    for cond in [f"$CI_COMMIT_BRANCH {cond_re}"]
    for stage, needs in (
      [(stage_deploy_de,needs_de(aggr))] if mode == "de" else
      [(stage_deploy_cl,needs_rt(info))] if mode == "cl" else
      [(stage_deploy_sp,needs_rt(info))]
    )
    for key_mask in [caption_mask.replace("$C4PROJ",proj_name)]
    for script in [[
      "export C4SUBJ=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{CI_COMMIT_BRANCH}')",
      "export C4USER=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{GITLAB_USER_LOGIN}')",
      "env | grep C4 | sort",
      handle(f"deploy {mode}-{arg}-{proj_name}-{opt} $CI_COMMIT_BRANCH")
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
  def forward(when): return common_job("$CI_COMMIT_TAG =~ /\\/de-/",when,"start",[],[
    handle("deploy fc-$(perl -e '/\\bde-(\w+-\w+-\w+)/&&print$1 for $ENV{CI_COMMIT_TAG}') no-branch")
  ])

  start_name = "start"
  check_name = "check"
  testing_name = "testing"
  return {
    start_name: {
      **common_job("$CI_COMMIT_TAG","on_success","start",[],[docker_conf(),handle("up $CI_ENVIRONMENT_SLUG")]),
      "environment": { "name": "$CI_COMMIT_TAG", "action": "start", "on_stop": "stop" }
    },
    check_name: common_job("$CI_COMMIT_TAG","on_success","check",[start_name],[handle("check")]),
    testing_name: common_job(cond_qa,"on_success","testing",[check_name],[handle("qa_run /c4/qa")]),
    "stop": stop("$CI_COMMIT_TAG","manual",[start_name]),
    "auto-stop": stop(cond_qa,"on_success",[testing_name]),
    "forward": forward("manual"),
    "auto-forward": forward("on_success"),
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