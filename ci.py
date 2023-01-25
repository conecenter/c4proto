import os
import typing
from c4util.build import temp_dev_pod, kcd_run, run, crane_login, secret_part_to_text, get_repo, never, setup_parser

class Options(typing.NamedTuple):
    pull_secret_name: str
    image_prefix: str

# def ci_img_list(opt: Options):
#     secret = secret_part_to_text(f"{opt.pull_secret_name}/.dockerconfigjson")
#     crane_login(secret)
#     run(("crane", "ls", get_repo(opt.image_prefix)))

def prod(args):
    proto_dir = os.environ["C4CI_PROTO_DIR"]
    run(("perl",f"{proto_dir}/prod.pl", *args))

def handle_up(opt):
    with temp_dev_pod({"imagePullSecrets": [{"name": "c4pull"}], "image": f"{opt.image}.ce"}) as pod_name:
        run(("c4dsync", "-acr", "--files-from", "-", "/", f"{pod_name}:/"), text=True,
            input=f"{os.environ['HOME']}/.kube")
        kcd_run("exec", pod_name, "--", "c4ci", "up_inner", "--app", opt.app)

def handle_up_inner(opt):
    prod(("ci_up",f"{opt.app}-env"))
    prod(("ci_check",f"{opt.app}-env"))

#
# def main(script, op, *args):
#     if op == "up":
#         prod(("ci_up",*args))
#         prod(("ci_check",*args))
#     if op == "down":
#         prod(("ci_down",*args))

def main():
    opt = setup_parser((
        ('up', handle_up, ("--image", "--app")),
        ('up_inner', handle_up_inner, ("--app"))
    )).parse_args()
    opt.op(opt)

main()
