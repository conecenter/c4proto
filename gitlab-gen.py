
import json
import base64
from c4util import group_map, read_json

### util


def ext(f): return lambda arg: f(*arg)

###

def docker_conf(): return call("C4GITLAB_DOCKER_CONF_BODY")+" > $C4CI_DOCKER_CONFIG"
def call(var): return f"echo ${var} | base64 -d | python3 -u"
def define(lines): return base64.b64encode("\n".join(lines).encode('utf-8')).decode('utf-8')

def def_push_rule(cond):
  return { "if": f"$CI_PIPELINE_SOURCE == \"push\" && {cond}" }

def use_push_rule(proj_sub, cond_id, when):
  return f".push_rule.{proj_sub}.{cond_id}.{when}"

def esc_slashes(v): return v.replace("/","\\/")
def prefix_cond(v):
    return f"$CI_COMMIT_BRANCH =~ /{esc_slashes(v)}/" if v else "$CI_COMMIT_BRANCH"


build_common_name = "build common"
stage_deploy_de = "develop"
stage_confirm = "confirm"
stage_deploy_sp = "confirm"
stage_deploy_cl = "deploy"


# def get_build_jobs(config_statements):
#   #aggr_cond_list = config_statements["C4AGGR_COND"]
#   #aggr_to_cond_list = group_map(aggr_cond_list, ext(lambda aggr, cond: (aggr,cond)))
#   #aggr_to_cond = { aggr: one(*cond_list) for aggr, cond_list in aggr_to_cond_list.items() }
#   return



# build aggr jobs -- C4AGGR_COND
# build fin jobs -- C4TAG_AGGR
# deploy jobs -- C4DEPLOY > C4TAG_AGGR

#def optional_job(name): return { "job":name, "optional":True }
def get_deploy_jobs(env_mask, caption_mask, proj_sub):
    rule = use_push_rule(proj_sub, "prod" if env_mask == "cl-prod" else "any", "manual")
    stage = (
      stage_deploy_de if env_mask.startswith("de-") else
      stage_deploy_cl if env_mask.startswith("cl-") else
      stage_deploy_sp
    )
    key_mask = caption_mask.replace("$C4PROJ_SUB", proj_sub)
    confirm_key = key_mask.replace("$C4CONFIRM", "confirm")
    confirm_key_opt = [confirm_key] if confirm_key != key_mask else []
    confirm_job_opt = [(k, {"extends": [".confirm", rule]}) for k in confirm_key_opt]
    return [
        *confirm_job_opt,
        (key_mask.replace("$C4CONFIRM", "deploy"), {
           "extends": [".deploy", rule], "variables": {"C4CI_ENV_MASK": f"{env_mask}-{proj_sub}"},
           **need(stage, [*confirm_key_opt, build_common_name]),
        })
    ]


def need(stage, needs):
    return {"stage": stage, "needs": needs}

def common_job(script):
    return {"image": "$C4COMMON_IMAGE", "variables": {"GIT_STRATEGY": "none"}, "script": script}

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
      "C4GITLAB_PAR_USER_BODY": define((
        'from os import environ as e',
        'print(e["C4CI_ENV_MASK"].replace("{C4SUBJ}",e["C4SUBJ"]).replace("{C4USER}",e["C4USER"]))',
      ))
    },
    "stages": ["build_common", "build_main", "develop", "confirm", "deploy", "start", "stop"],
    build_common_name: {
      "extends": [".build_common"], "rules": [def_push_rule(prefix_cond(""))], **need("build_common", []),
      "image": "$C4COMMON_BUILDER_IMAGE", "script": [docker_conf(), "$C4COMMON_BUILDER_CMD"],
    },
    ".build_rt": {
      **common_job([docker_conf(), f"c4ci build --proj-tag $C4CI_PROJ_TAG --push-secret $C4CI_DOCKER_CONFIG"]),
      **need("build_main", [build_common_name]),
    },
    **{
      f"b {tag} {aggr} rt": {
        "extends": [".build_rt", use_push_rule(aggr, "any", "on_success")], "variables": {"C4CI_PROJ_TAG": tag}
      }
      for tag, aggr in config_statements["C4TAG_AGGR"]
    },
    ".confirm": {**common_job(["echo confirming"]), **need("confirm", [build_common_name])},
    ".deploy": {
      **common_job([
        docker_conf(),
        "export C4SUBJ=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{CI_COMMIT_BRANCH}')",
        "export C4USER=$(perl -e 's{[^a-zA-Z/]}{}g,/(\w+)$/ && print lc $1 for $ENV{GITLAB_USER_LOGIN}')",
        "export C4CI_ENV_BASE=$("+call("C4GITLAB_PAR_USER_BODY")+")",
        f"export C4CI_ENV_NAME=$C4CI_ENV_BASE-env",
        "c4ci ci_wait_images $C4CI_ENV_NAME",
        "c4ci ci_push $C4CI_ENV_NAME",
        f"c4ci ci_get /tmp/c4ci-env_group $C4CI_ENV_NAME/ci:env_group /tmp/c4ci-hostname $C4CI_ENV_BASE-gate/ci:hostname",
        "export C4CI_ENV_GROUP=$(cat /tmp/c4ci-env_group)",
        "export C4CI_ENV_URL=https://$(cat /tmp/c4ci-hostname)",
        call("C4GITLAB_DEPLOY_BODY"),
        f"c4ci ci_check_images $C4CI_ENV_NAME",
      ]),
    },
    **{
      key: value
      for env_mask, caption_mask in config_statements["C4DEPLOY"]
      for proj_sub, cond_pre in config_statements["C4AGGR_COND"] if cond_pre
      for key, value in get_deploy_jobs(env_mask, caption_mask, proj_sub)
    },
    **{
      action: {
        **common_job([f"c4ci {ci_act} $C4CI_ENV_NAME"]), **need(action, []),
        "rules": [{"if": f"$CI_PIPELINE_SOURCE == \"api\" && $C4CI_ENV_NAME", "when": when}],
        "environment": {"name": "$C4CI_ENV_GROUP/$C4CI_ENV_NAME", "action": action, **add_env}
      }
      for (when, action, add_env, ci_act) in (
        ("on_success", "start", {"on_stop": "stop", "url": "$C4CI_ENV_URL"}, "ci_up"),
        ("manual", "stop", {}, "ci_down"),
      )
    },
    **{
        use_push_rule(proj_sub, cond_id, when): {"rules": [{**def_push_rule(cond), "when": when}]}
        for proj_sub, cond_pre in config_statements["C4AGGR_COND"] if cond_pre
        for when, cond_id, cond in (
            ("on_success", "any", prefix_cond(cond_pre)),
            ("manual", "any", prefix_cond(cond_pre)),
            ("manual", "prod", prefix_cond(cond_pre)+" && "+prefix_cond("/release/"))
        )
    },
  }
  print(json.dumps(out, sort_keys=True, indent=4))



main()

# python3 c4proto/gitlab-gen.py < c4dep.main.json > gitlab-ci-generated.0.yml

#docker run --rm -v $PWD:/build_dir -e C4CI_BUILD_DIR=/build_dir python:3.8 python3 /build_dir/c4proto/gitlab-gen.py