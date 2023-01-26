import json
import os
import typing
from c4util.build import kcd_run, run, setup_parser, need_pod, temp_dev_pod, apply_manifest, kcd_args


class Options(typing.NamedTuple):
    pull_secret_name: str
    image_prefix: str


# def ci_img_list(opt: Options):
#     secret = secret_part_to_text(f"{opt.pull_secret_name}/.dockerconfigjson")
#     crane_login(secret)
#     run(("crane", "ls", get_repo(opt.image_prefix)))

def prod(args):
    proto_dir = os.environ["C4CI_PROTO_DIR"]
    run(("perl", f"{proto_dir}/prod.pl", *args))


def sync_kube_conf(pod_name):
    run(("c4dsync", "-acr", "--files-from", "-", "/", f"{pod_name}:/"), text=True, input=f"{os.environ['HOME']}/.kube")


def get_pod_options(image):
    return {"imagePullSecrets": [{"name": "c4pull"}], "image": f"{image}.ce"}


def get_secret_name(env_name):
    return f"c4envopt-{env_name}"


def get_env_image(env_name):
    secret_name = get_secret_name(env_name)
    args = kcd_args("get", "secret", secret_name, "-o", "json")
    secret = json.loads(run(args, text=True, capture_output=True).stdout)
    return secret["metadata"]["annotations"]["c4image"]


def set_env_image(env_name, image):
    if image:
        apply_manifest({
            "apiVersion": "v1", "kind": "Secret", "data": {},
            "metadata": {"name": get_secret_name(env_name), "annotations": {"c4image": image}}
        })
    else:
        kcd_run("delete", "secret", get_secret_name(env_name))


def handle_up(opt):
    with temp_dev_pod(get_pod_options(opt.image)) as pod_name:
        sync_kube_conf(pod_name)
        kcd_run("exec", pod_name, "--", "c4ci", "up_inner", "--env-name", opt.env_name)
        set_env_image(opt.env_name, opt.iimage)


def handle_up_inner(opt):
    prod(("ci_up", f"{opt.env_name}-env"))
    prod(("ci_check", f"{opt.env_name}-env"))


def handle_down(opt):
    image = get_env_image(opt.env_name)
    with temp_dev_pod(get_pod_options(image)) as pod_name:
        sync_kube_conf(pod_name)
        kcd_run("exec", pod_name, "--", "c4ci", "down_inner", "--env-name", opt.env_name)
        set_env_image(opt.env_name, None)


def handle_down_inner(opt):
    prod(("ci_down", f"{opt.env_name}-env"))


def main():
    opt = setup_parser((
        ('up', handle_up, ("--image", "--env-name")),
        ('up_inner', handle_up_inner, ("--env-name",)),
        ('down', handle_down, ("--env-name",)),
        ('down_inner', handle_down_inner, ("--env-name",)),
    )).parse_args()
    opt.op(opt)


main()
