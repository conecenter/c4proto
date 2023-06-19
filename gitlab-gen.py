
import json
import sys
import os
from pathlib import Path
import tempfile
from c4util import changing_text, read_json, one, read_text
from c4util.build import run, secret_part_to_text, build_cached_by_content, get_repo


def main(script, build_path):
    with tempfile.TemporaryDirectory() as temp_root:
        changing_text(f"{temp_root}/c4prep", read_text(f"{os.environ['HOME']}/bin/c4prep"))
        changing_text(f"{temp_root}/Dockerfile", "\n".join((
            "FROM ubuntu:22.04",
            "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /replink.pl /",
            "RUN perl install.pl useradd 1979",
            "RUN /install.pl curl https://get.helm.sh/helm-v3.12.1-linux-amd64.tar.gz",
            "COPY c4prep /tools", "RUN chmod +x /tools/c4prep",
        )))
        plain_conf = read_json(f"{build_path}/c4dep.main.json")
        repo = get_repo(one(*(line[2] for line in plain_conf if line[0] == "C4COMMON_IMAGE_PREFIX")))
        push_secret = secret_part_to_text("c4push/.dockerconfigjson")
        image = build_cached_by_content(temp_root, repo, push_secret)
        out = {".handler": {"image": image}}
        changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4))


main(*sys.argv)
