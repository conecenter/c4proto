
import json
import tempfile
import base64
import pathlib
from c4util import read_json, changing_text
from build_util import run, kcd_args, kcd_run, need_pod, build_cached_by_content, setup_parser

###

def get_pod_name(user_config,i):
    user = read_json(user_config)["devName"]
    return f"te-{user}-v0-i{int(i)}"

def secret_to_dir(name, dir):
    dir_path = pathlib.Path(dir)
    args = kcd_args("get", "secret", name, "-o", "json")
    secret = json.loads(run(args,text=True,capture_output=True).stdout)
    for key, value in secret["data"].items():
        (dir_path / key).write_bytes(base64.b64decode(value))

def get_test_env_image(repository, push_secret_from_k8s):
    with tempfile.TemporaryDirectory() as auth_dir:
        push_secret_name, secret_fn = push_secret_from_k8s.split("/")
        push_secret = f"{auth_dir}/{secret_fn}"
        secret_to_dir(push_secret_name, auth_dir)
        with tempfile.TemporaryDirectory() as temp_root:
            content = "\n".join((
                "FROM ubuntu:22.04",
                "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
                "RUN perl install.pl useradd 1979",
                "RUN perl install.pl apt curl ca-certificates rsync openjdk-17-jdk-headless maven",
                "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini"+
                " && chmod +x /tools/tini",
                "USER c4",
            ))
            changing_text(f"{temp_root}/Dockerfile", content, None)
            return build_cached_by_content(temp_root, repository, push_secret)

def handle_sync(opt):
    name = get_pod_name(opt.user_config, opt.env_id)
    need_pod(name, lambda: {
        "command": ["/tools/tini", "--", "sleep", "infinity"],
        "imagePullSecrets": [{ "name": opt.pull_secret_name }],
        "image": get_test_env_image(opt.repository, opt.push_secret_from_k8s)
    })
    files = ("src",)
    #run(("c4dsync","-acr","--del","--files-from","-",f"{opt.from_dir}/",f"{name}:{opt.to_dir}"), text=True, input="\n".join(files))
    run(("c4dsync","-acr","--del","--exclude",".git/",f"{opt.from_dir}/",f"{name}:{opt.to_dir}"))

def handle_rm(opt):
    name = get_pod_name(opt.user_config, opt.env_id)
    kcd_run("delete", "pod", name)

def main():
    opt = setup_parser((
        ("sync", handle_sync, ("--from-dir","--to-dir","--repository","--push-secret-from-k8s","--pull-secret-name","--user-config","--env-id")),
        ("rm", handle_rm, ("--user-config","--env-id"))
    )).parse_args()
    opt.op(opt)

main()
