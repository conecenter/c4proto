
import json
import sys
from c4util import changing_text


def replink(dir, fn):
    return f"C4CI_BUILD_DIR={dir} C4REPO_MAIN_CONF={dir}/{fn} /replink.pl"


def main(build_path):
    out = {
        ".build_common": {
            "image": "ghcr.io/conecenter/c4replink:v3kc",
            "variables": {
                "C4PYTHON": "/usr/bin/python3",
                "C4COMMON_BUILDER_CMD": " && ".join((
                  replink("$CI_PROJECT_DIR","c4dep.ci.replink"), replink("$C4COMMON_PROTO_DIR","c4dep.main.replink"),
                  "C4CI_PROTO_DIR=$C4COMMON_PROTO_DIR" +
                  " python3 -u $C4CI_PROTO_DIR/run_with_prefix.py time" +
                  " python3 -u $C4CI_PROTO_DIR/build_remote.py build_common" +
                  " --build-dir $C4CI_BUILD_DIR --push-secret $C4CI_DOCKER_CONFIG --context $CI_PROJECT_DIR" +
                  " --image $C4COMMON_IMAGE --commit $CI_COMMIT_SHORT_SHA --java-options \"$C4BUILD_JAVA_TOOL_OPTIONS\""
                ))
            }
        }
    }
    changing_text(f"{build_path}/gitlab-ci-generated.yml", json.dumps(out, sort_keys=True, indent=4))


main(*sys.argv[1:])
