import json
import os

from c4proto.c4util import one
from c4util.build import kcd_run, run, setup_parser, temp_dev_pod, kcd_args


def sync_kube_conf(pod_name):
    run(("c4dsync", "-acr", "--files-from", "-", "/", f"{pod_name}:/"), text=True, input=f"{os.environ['HOME']}/.kube")


def get_pod_options(image):
    return {"imagePullSecrets": [{"name": "c4pull"}], "image": f"{image}.ce"}


def run_inner(pod_name, *args):
    kcd_run("exec", pod_name, "--", "c4ci", *args)


def handle_up(opt):
    with temp_dev_pod(get_pod_options(opt.image)) as pod_name:
        sync_kube_conf(pod_name)
        run_inner(pod_name, "ci_up", f"{opt.env_name}-env")


def make_down(env_name):
    with temp_dev_pod(get_pod_options(get_env_image(env_name))) as pod_name:
        sync_kube_conf(pod_name)
        run_inner(pod_name, "ci_down", f"{env_name}-env")


def handle_down(opt):
    make_down(opt.env_name)


def get_env_image(env_name):
    args = kcd_args("get", "deploy", "-l", f"c4env={env_name}", "-o", "json")
    res = json.loads(run(args, text=True, capture_output=True).stdout)
    return one(*set(
        e["value"]
        for d in res["items"]
        for c in d["spec"]["template"]["spec"]["containers"]
        for e in c["env"] if e["name"] == "C4COMMON_IMAGE"
    ))


def get_names_from_ids(template, ids):
    return [template.replace("%i", str(i)) for i in ids]


def handle_clones_up(opt):
    from_env, *to_env_names = get_names_from_ids(f"{opt.env_name}-env", ("", *opt.clone_ids))
    with temp_dev_pod(get_pod_options(get_env_image(opt.env_name))) as pod_name:
        sync_kube_conf(pod_name)
        for to_env in to_env_names:
            run_inner(pod_name, "ci_up", to_env)
        for to_env in to_env_names:
            run_inner(pod_name, "ci_check_images", to_env)
        run_inner(pod_name, "ci_setup", "--from", from_env, "--to", ",".join(to_env_names))


def handle_clones_down(opt):
    for env_name in get_names_from_ids(opt.env_name, opt.clone_ids):
        make_down(env_name)


def main():
    opt = setup_parser((
        ('up', handle_up, ("--image", "--env-name")),
        ('down', handle_down, ("--env-name",)),
        ('clones_up', handle_clones_up, ("--env-name", "--clone-ids")),
        ('clones_down', handle_clones_down, ("--env-name", "--clone-ids")),
    )).parse_args()
    opt.op(opt)


main()
