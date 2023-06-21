
import json
import sys
import tempfile
from c4util import changing_text, read_text
from c4util.build import build_cached_by_content, get_main_conf


def main(build_path):
    with tempfile.TemporaryDirectory() as temp_root:
        get_plain_option = get_main_conf(build_path)
        proto_postfix = get_plain_option("C4PROTO_POSTFIX")
        repo = get_plain_option("C4CI_IMAGE_REPO")
        changing_text(f"{temp_root}/c4op", read_text(f"{build_path}/{proto_postfix}/build_op.py"))
        changing_text(f"{temp_root}/Dockerfile", "\n".join((
            "FROM ubuntu:22.04",
            "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
            "RUN perl install.pl useradd 1979",
            "RUN /install.pl curl https://get.helm.sh/helm-v3.12.1-linux-amd64.tar.gz",
            "COPY c4op /tools", "RUN chmod +x /tools/c4op",
        )))
        image = build_cached_by_content(temp_root, repo, "c4push/.dockerconfigjson")
        out = {".handler": {"image": image}}
        changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4))


main(*sys.argv[1:])
