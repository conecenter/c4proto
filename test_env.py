import json
import os
import tempfile
import time
import typing
from .c4util import read_json, changing_text, read_text, path_exists
from .c4util.build import run, kcd_run, need_pod, \
    build_cached_by_content, never, Popen, temp_dev_pod, kcd_args, run_text_out, \
    get_env_values_from_deployments


class Options(typing.NamedTuple):
    user_config: str
    repository: str
    pull_secret_name: str
    push_secret_from_k8s: str


def get_pod_name_prefix(user_config: str):
    user = read_json(user_config)["devName"]
    return f"te-{user}-v01-i"


def get_test_env_image(repository, push_secret_from_k8s):
    with tempfile.TemporaryDirectory() as temp_root:
        content = "\n".join((
            "FROM ubuntu:22.04",
            "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
            "RUN perl install.pl useradd 1979",
            "RUN perl install.pl apt curl ca-certificates rsync openjdk-17-jdk-headless maven python3",
            "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
            " && chmod +x /tools/tini",
            "USER c4",
        ))
        changing_text(f"{temp_root}/Dockerfile", content)
        return build_cached_by_content(temp_root, repository, push_secret_from_k8s)


def need_env(opt: Options, env_id: int):
    name = get_pod_name_prefix(opt.user_config) + str(int(env_id))
    need_pod(name, lambda: {
        "command": ["/tools/tini", "--", "sleep", "infinity"],
        "imagePullSecrets": [{"name": opt.pull_secret_name}],
        "image": get_test_env_image(opt.repository, opt.push_secret_from_k8s)
    })
    return name


def sync(context: str, pod_name: str):
    run((
        "c4dsync", "-acr", "--del", "--exclude", ".git/",
        f"{context}/", f"{pod_name}:{context}"
    ))


def get_selected():
    return read_text("/tmp/c4pod")


def delete(pod_name: str):
    kcd_run("delete", "pod", pod_name)


is_digit_set = set("0123456789")


def is_digits(value):
    return all(c in is_digit_set for c in value)


def check_env_id(opt: Options, pod_name: str):
    dummy, prefix, env_id = pod_name.partition(
        get_pod_name_prefix(opt.user_config)
    )
    if dummy or not (prefix and env_id and is_digits(env_id)):
        never(f"bad env version: {dummy} {prefix} {env_id}")


def run_py_in_env(pod_name: str, args):
    kcd_run("exec", pod_name, "--", "python3", "-u", *args)


def find_java_pid():
    lines = run_text_out(("jcmd",)).splitlines()
    return next((int(l.split()[0]) for l in lines if "jcmd" not in l), None)


def need_java_task_tail(start_stm, log):
    pid = find_java_pid()
    if pid:
        print(f"{pid} is active")
    else:
        run(("bash", "-c", start_stm))
    tail_proc = Popen(("tail", "-F", "-n", "+0", log))
    while True:
        time.sleep(1 if pid else 2)
        pid = pid if pid and path_exists(f"/proc/{pid}") else find_java_pid()
        if not pid:
            break


def sync_kube_conf(pod_name):
    run(("c4dsync", "-acr", "--files-from", "-", "/", f"{pod_name}:/"), text=True, input=f"{os.environ['HOME']}/.kube")


def get_pod_options(image):
    return {"imagePullSecrets": [{"name": "c4pull"}], "image": f"{image}.ce"}


def run_inner(pod_name, *args):
    kcd_run("exec", pod_name, "--", "c4ci", *args) # todo restore


def get_env_images(env_name):
    deployments = json.loads(run_text_out(kcd_args("get", "deploy", "-l", f"c4env={env_name}", "-o", "json")))["items"]
    images = sorted(get_env_values_from_deployments("C4COMMON_IMAGE", deployments))  # no more C4COMMON_IMAGE
    if len(images) > 1: never("bad image")
    return images


def get_names_from_ids(template, ids):
    return [template.replace("%i", str(i)) for i in ids]


def clones_up(env_template, clone_ids):
    clones_down(env_template, clone_ids)
    to_env_names = get_names_from_ids(f"{env_template}-env", clone_ids)
    from_env, = get_names_from_ids(env_template, ("",))
    image, = get_env_images(from_env)
    with temp_dev_pod(get_pod_options(image)) as pod_name:
        sync_kube_conf(pod_name)
        run_inner(pod_name, "ci_clone", "--from", f"{from_env}-env", "--to", ",".join(to_env_names)) # todo restore ci_clone
        for to_env in to_env_names:
            run_inner(pod_name, "ci_up", to_env) # todo restore
        for to_env in to_env_names:
            run_inner(pod_name, "ci_check_images", to_env) # todo restore
        # for to_env in to_env_names:
        #     run_inner(pod_name, "ci_check_availability", to_env)


def clones_down(env_template, clone_ids):
    for env_name in get_names_from_ids(env_template, clone_ids):
        for image in get_env_images(env_name):
            with temp_dev_pod(get_pod_options(image)) as pod_name:
                sync_kube_conf(pod_name)
                run_inner(pod_name, "ci_down", f"{env_name}-env") # todo restore
