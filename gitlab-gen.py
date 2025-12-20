from base64 import b64encode
from json import dumps, loads
from os import environ
from subprocess import check_output
from sys import argv
from pathlib import Path
from re import findall

def changing(path: Path, data: bytes): path.exists() and path.read_bytes() == data or path.write_bytes(data)

def main(kube_context, context):
    ld = lambda nm: (Path(__file__).parent / nm).read_bytes()
    content = ld("build.def.dockerfile").decode()
    names_to_copy = findall(r"COPY\s+--from=c4emb\s+/(\S+).*", content)
    add = " && ".join(f"echo '{b64encode(ld(s)).decode()}' | base64 -d > /{s}" for s in names_to_copy)
    res_content = "\n".join(("### THIS FILE IS GENERATED ###","FROM ubuntu:24.04 AS c4emb",f"RUN {add}",content))
    changing(Path(f"{context}/Dockerfile"), res_content.encode())
    #
    build = ("python3", "-u", str(Path(__file__).parent / "ci_build.py"))
    args = ("--kube-context", kube_context, "--context", f'{context}/Dockerfile', "--opt", dumps({"image_type": "ci"}))
    image = check_output((*build, *args)).decode().strip()
    out = {".handler": {"image": image}}
    changing(Path(f"{context}/gitlab-ci-generated.yml"), dumps(out, sort_keys=True, indent=4).encode())

main(environ["C4DEPLOY_CONTEXT"], *argv[1:])
