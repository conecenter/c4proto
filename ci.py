import os
import typing
from c4util.build import kcd_run, run, setup_parser, need_pod


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


def handle_up(opt):
    pod_name = f"ce-{opt.app}"
    need_pod(pod_name, lambda: {"imagePullSecrets": [{"name": "c4pull"}], "image": f"{opt.image}.ce"})
    sync_kube_conf(pod_name)
    kcd_run("exec", pod_name, "--", "c4ci", "up_inner", "--app", opt.app)


def handle_up_inner(opt):
    prod(("ci_up", f"{opt.app}-env"))
    prod(("ci_check", f"{opt.app}-env"))


def handle_down(opt):
    pod_name = f"ce-{opt.app}"
    sync_kube_conf(pod_name)
    kcd_run("exec", pod_name, "--", "c4ci", "down_inner", "--app", opt.app)
    kcd_run("delete", "pod", pod_name)


def handle_down_inner(opt):
    prod(("ci_down", f"{opt.app}-env"))


def main():
    opt = setup_parser((
        ('up', handle_up, ("--image", "--app")),
        ('up_inner', handle_up_inner, ("--app",)),
        ('down', handle_down, ("--app",)),
        ('down', handle_down_inner, ("--app",)),
    )).parse_args()
    opt.op(opt)


main()
