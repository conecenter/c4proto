
import tempfile
import typing
from .c4util import read_json, changing_text, read_text
from .c4util.build import run, kcd_run, need_pod, \
    build_cached_by_content, secret_to_dir_path


class Options(typing.NamedTuple):
    user_config: str
    repository: str
    pull_secret_name: str
    push_secret_from_k8s: str


def get_pod_name(user_config: str, i: int):
    user = read_json(user_config)["devName"]
    return f"te-{user}-v0-i{int(i)}"


def get_test_env_image(repository, push_secret_from_k8s):
    with tempfile.TemporaryDirectory() as auth_dir:
        push_secret = secret_to_dir_path(push_secret_from_k8s, auth_dir)
        with tempfile.TemporaryDirectory() as temp_root:
            content = "\n".join((
                "FROM ubuntu:22.04",
                "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
                "RUN perl install.pl useradd 1979",
                "RUN perl install.pl apt curl ca-certificates rsync openjdk-17-jdk-headless maven",
                "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini" +
                " && chmod +x /tools/tini",
                "USER c4",
            ))
            changing_text(f"{temp_root}/Dockerfile", content)
            return build_cached_by_content(temp_root, repository, push_secret)


def need_env(opt: Options, env_id: int):
    name = get_pod_name(opt.user_config, env_id)
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


def run_in_env(pod_name: str, args):
    kcd_run("exec", pod_name, "--", *args)
