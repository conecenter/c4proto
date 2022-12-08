
import sys
import os
import subprocess
import json
import gitlab
import re
from c4util import read_json, sha256

def get_env(k):
    v = os.environ[k]
    print(f"export {k}='{v}'")
    return v

def get_project():
    token = get_env("C4CI_TOKEN")
    project_id = get_env("CI_PROJECT_ID")
    server_url = get_env("CI_SERVER_URL")
    return gitlab.Gitlab(server_url, private_token=token).projects.get(project_id)

def set_tag(project,tag_name,commit):
    try:
        project.tags.get(tag_name).delete()
    except gitlab.GitlabGetError:
        pass
    print(f"creating tag [{tag_name}] [{commit}]")
    project.tags.create({'tag_name':tag_name,'ref':commit})

def prod(args):
    proto_dir = get_env("C4CI_PROTO_DIR")
    subprocess.run(["perl",f"{proto_dir}/prod.pl"] + args).check_returncode()

def need_environment(project,slug):
    environments = project.environments.list(all=True)
    found = [e for e in environments if e.slug == slug]
    return found[0] if len(found)==1 else project.environments.create({"name":slug}) if len(found)==0 else None

def ci_info_path():
    return "/tmp/c4ci-info.json"

def query_ci_info(name):
    path = ci_info_path()
    prod(["ci_info",name,path])
    return read_json(path)

def get_slug(info):
    return "c4-"+sha256(info["env"])[0:8]

# re.findall(r'[^/]+',arg_raw)[-1]  re.sub(r'\W+','',arg_raw_last)  sha256(v)[0:5]  re.fullmatch("(\w+)/(.+)",branch).groups()
# f"{mode}-{arg}-{proj_name}-{opt}"
def handle_deploy(base,branch):
    commit = get_env("CI_COMMIT_SHA")
    project_url = get_env("CI_PROJECT_URL")
    name = f"{base}-env"
    info = query_ci_info(name)
    slug = get_slug(info)
    project = get_project()
    hostnames = [c["hostname"] for c in info["ci_parts"] if "hostname" in c]
    print("hostnames",hostnames)
    env_group = info["env_group"]
    tag_name = f"{env_group}/{base}/{branch}"
    environment = need_environment(project,slug)
    environment.name = tag_name
    if len(hostnames) > 0: environment.external_url = f"https://{min(hostnames)}"
    environment.save()
    environment_url = f"{project_url}/-/environments/{environment.get_id()}"
    print(f"deploy environment: {environment_url}")
    #prod(["ci_push",name])
    set_tag(project,tag_name,commit)
    prod(["ci_check_images",name])
    #prod(["ci_check",name])

def get_c4env_from_tag():
    return re.findall(r'[^/]+',get_env("CI_COMMIT_TAG"))[1] + "-env"

def handle_down():
    prod(["ci_down",get_c4env_from_tag()])

def handle_up(s_slug):
    name = get_c4env_from_tag()
    info = query_ci_info(name)
    f_slug = get_slug(info)
    if s_slug != f_slug: raise Exception(f"{s_slug} != {f_slug}")
    prod(["ci_push",name])
    prod(["ci_up",name])

def handle_qa_run(dir):
    name = get_c4env_from_tag()
    prod(["ci_setup",name])
    info = query_ci_info(name)
    subprocess.run(["cat",ci_info_path()]).check_returncode()
    qa_run = info["qa_run"]
    subprocess.run(["git","clone",get_env("C4QA_REPO"),dir]).check_returncode()
    subprocess.run(["git","checkout",info["qa_ref"]],cwd=dir).check_returncode()
    subprocess.run(["chmod","+x",f"./{qa_run}"],cwd=dir).check_returncode()
    subprocess.run([f"./{qa_run}",ci_info_path()],cwd=dir).check_returncode()

def handle_check():
    name = get_c4env_from_tag()
    prod(["ci_check",name])

def handle_rebuild(branch_arg):
    commit = get_env("CI_COMMIT_SHA")
    postfix = f".rebuild.{commit}"
    branch = branch_arg if branch_arg.endswith(postfix) else f"{branch_arg}{postfix}"
    project = get_project()
    try:
        project.branches.get(branch).delete()
    except gitlab.GitlabGetError:
        pass
    project.branches.create({'branch': branch, 'ref': commit})


# def get_stop_url():
#     pipeline_id = get_env("CI_PIPELINE_ID")
#     api_url = get_env("CI_API_V4_URL")
#     project_id = get_env("CI_PROJECT_ID")
#     project = get_project()
#     pipeline = project.pipelines.get(pipeline_id)
#     [job_id] = [j.id for j in pipeline.jobs.list() if j.name == 'stop']
#     return f"{api_url}/projects/{project_id}/jobs/{job_id}/play"

# def handle_notify_tester(name):
#     info = query_ci_info(name)
#     if "notify_url" not in info:
#         return
#     data = json.dumps({ "parts": info["ci_parts"] })
#     print(data)
#     postReq =  urllib.request.Request(
#         method = "POST",
#         url = info["notify_url"],
#         data = data.encode('utf-8'),
#         headers = {"Content-Type": "application/json; utf-8"}
#     )
#     postResp = urllib.request.urlopen(postReq)
#     if postResp.status!=200:
#         raise Exception("req sending failed")

handle = {
    "deploy": handle_deploy,
    "up": handle_up,
    "check": handle_check,
    "qa_run": handle_qa_run,
    "down": handle_down,
    "rebuild": handle_rebuild
}

script, act, *args = sys.argv
handle[act](*args)
