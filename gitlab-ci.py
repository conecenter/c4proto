
import sys
import os
import subprocess
import hashlib
import json
import gitlab
import re

def get_env(k):
    v = os.environ[k]
    print(f"export {k}='{v}'")
    return v

def get_project():
    token = get_env("C4CI_TOKEN")
    project_id = get_env("CI_PROJECT_ID")
    server_url = get_env("CI_SERVER_URL")
    return gitlab.Gitlab(server_url, private_token=token).projects.get(project_id)

def get_branch():
    return get_env("CI_COMMIT_BRANCH")

def get_branch_parts(branch):
    return re.fullmatch("(\w+)/(.+)",branch).groups()

def set_tag(project,tag_name,commit):
    try:
        project.tags.get(tag_name).delete()
    except gitlab.GitlabGetError:
        pass
    print(f"creating tag [{tag_name}] [{commit}]")
    project.tags.create({'tag_name':tag_name,'ref':commit})

def prod(args):
    proto_dir = get_env("C4CI_PROTO_DIR")
    subprocess.run(["ssh-agent","perl",f"{proto_dir}/prod.pl"] + args).check_returncode()

def read_json(path):
    with open(path,'r') as f:
        return json.load(f)

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

def deploy(tag_name):
    commit = get_env("CI_COMMIT_SHA")
    info = query_ci_info(tag_name)
    slug = info["env"]
    project = get_project()
    hostnames = [c["hostname"] for c in info["ci_parts"] if "hostname" in c]
    print("hostnames",hostnames)
    environment = need_environment(project,slug)
    environment.name = tag_name
    if len(hostnames) > 0: environment.external_url = f"https://{min(hostnames)}"
    environment.save()
    project_url = get_env("CI_PROJECT_URL")
    environment_url = f"{project_url}/-/environments/{environment.get_id()}"
    print(f"deploy environment: {environment_url}")
    set_tag(project,tag_name,commit)

def sha256(v):
    return hashlib.sha256(v.encode('utf-8')).hexdigest()

def handle_test(mode,active_count):
    job_id = get_env("CI_JOB_ID")
    branch = get_branch()
    proj_name, hint = get_branch_parts(branch)
    deploy(f"{proj_name}/{mode}-{sha256(branch)[0:5]}-{job_id}-{active_count}/{hint}")

def handle_deploy(arg):
    branch = get_branch()
    proj_name, hint = get_branch_parts(branch)
    deploy(f"{proj_name}/{arg}/{hint}")

def handle_deploy_dev(arg):
    dev_name = re.sub(r'\W+','',get_env("GITLAB_USER_LOGIN"))
    branch = get_branch()
    proj_name, hint = get_branch_parts(branch)
    deploy(f"dev-{dev_name}/{proj_name}-{arg}/{hint}")

def handle_check(name,s_slug):
    info = query_ci_info(name)
    f_slug = info["env"]
    if s_slug != f_slug: raise Exception(f"{s_slug} != {f_slug}")



def handle_qa_run(name,dir):
    info = query_ci_info(name)
    subprocess.run(["cat",ci_info_path()]).check_returncode()
    qa_run = info["qa_run"]
    subprocess.run(["git","clone",get_env("C4QA_REPO"),dir]).check_returncode()
    subprocess.run(["git","checkout",info["qa_ref"]],cwd=dir).check_returncode()
    subprocess.run(["chmod","+x",f"./{qa_run}"],cwd=dir).check_returncode()
    subprocess.run([f"./{qa_run}",ci_info_path()],cwd=dir).check_returncode()

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
    "deploy_dev": handle_deploy_dev,
    "test": handle_test,
    "check": handle_check,
    "qa_run": handle_qa_run,
#    "notify_tester": handle_notify_tester
}

script, act, *args = sys.argv
handle[act](*args)
