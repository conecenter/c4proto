
import json
import sys
import tempfile
from c4util import changing_text, read_text
from c4util.build import build_cached_by_content, get_main_conf, get_proto, get_image_conf


def main(build_path):
    temp_root = tempfile.TemporaryDirectory()
    get_plain_option = get_main_conf(build_path)
    proto_postfix, proto_dir = get_proto(build_path, get_plain_option)
    repo, image_tag_prefix = get_image_conf(get_plain_option)
    changing_text(f"{temp_root.name}/c4ci_prep", read_text(f"{proto_dir}/ci_prep.py"))
    changing_text(f"{temp_root.name}/c4ci_up", read_text(f"{proto_dir}/ci_up.py"))
    steps = "\n".join((
        "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt curl ca-certificates python3 git libjson-xs-perl rsync",
        "RUN perl install.pl curl https://dl.k8s.io/release/v1.25.3/bin/linux/amd64/kubectl" +
        " && chmod +x /tools/kubectl",
        "RUN curl -L -o /t.tgz https://github.com/google/go-containerregistry/releases/download/v0.12.1/go-containerregistry_Linux_x86_64.tar.gz" +
        " && tar -C /tools -xzf /t.tgz crane && rm /t.tgz",
        "COPY c4ci_prep c4ci_up /tools/", "RUN chmod +x /tools/c4ci_prep /tools/c4ci_up",
        "ENV PATH=${PATH}:/tools:/tools/linux",
    ))
    changing_text(f"{temp_root.name}/Dockerfile", steps)
    image = build_cached_by_content(temp_root.name, repo, "c4push/.dockerconfigjson")
    out = {".handler": {"image": image}}
    changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4))


main(*sys.argv[1:])
