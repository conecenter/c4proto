from argparse import ArgumentParser
from base64 import b64decode
from json import loads
from pathlib import Path
from subprocess import check_output, DEVNULL, run
from sys import stderr
from time import time
from tempfile import TemporaryDirectory

def die(e): raise e

def da(*args):
    print(f"[{time()}]starting: " + " ".join(args), file=stderr)
    return args

# there are 3 cases:
# - --context has .git at its root and `git status` is clean => identity is commit hash "HEAD"
# - --context is outside repo or deep in repo => temp repo is created for its content, identity is tree hash "HEAD:" (content-addressed); Dockerfile is in temp root
# - --context is ".../Dockerfile" itself => same as previous, but other files are ignored

def main():
    parser = ArgumentParser()
    parser.add_argument("--kube-context", required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--opt", required=True)
    args = parser.parse_args()
    kube_context = args.kube_context
    context = Path(args.context)
    opt = loads(args.opt)
    project = opt.get("project")
    target = opt.get("image_type")
    to_repo_opt = opt.get("to_repo")
    only_source_repo = opt.get("only_source_repo")
    #
    kc = ["kubectl", "--context", kube_context]
    secret = loads(check_output(da(*kc, "get", "secret", "c4builder", "-o", "json")))["data"]
    from_repo = b64decode(secret["src_repo"]).decode()
    tmp_home_life = TemporaryDirectory()
    Path(f'{tmp_home_life.name}/.docker').mkdir()
    Path(f'{tmp_home_life.name}/.docker/config.json').write_bytes(b64decode(secret["config.json"]))
    crane = ("env", f"HOME={tmp_home_life.name}", "crane")
    tag_pf = f'.{project or "def"}.{target or "def"}'
    extra_repo = f'{tmp_home_life.name}/repo'
    out = f'{tmp_home_life.name}/out'
    Path(out).mkdir()
    #
    commit = (
        (context/".git").exists() and not check_output(("git","status","--porcelain"),cwd=str(context)).strip() and
        check_output(da("git", "rev-parse", "--short", "HEAD"), cwd=str(context)).decode().strip()
    )
    if commit:
        image_tag = "c42c." + commit + tag_pf
    else:
        worktree, add = (
            (context, ".") if context.is_dir() else
            (context.parent, context.name) if context.is_file() else
            die(Exception(f"bad context: {context}"))
        )
        check_output(da("git", "init", extra_repo))
        check_output(da("git", "--git-dir", f'{extra_repo}/.git', "--work-tree", str(worktree), "add", add))
        image_tag = "c42t." + check_output(da("git", "write-tree"), cwd=extra_repo).decode().strip()[:8] + tag_pf
    from_image = f'{from_repo}:{image_tag}'
    if run(da(*crane, "manifest", from_image), check=False, stdout=DEVNULL, stderr=DEVNULL).returncode != 0:
        if commit:
            data = check_output(da("git","archive","HEAD"), cwd=context)
            check_output(da("tar","-C",out,"-xf-"), input=data)
        else:
            check_output(da("git", "--git-dir", f'{extra_repo}/.git', "--work-tree", out, "checkout", "."))
        check_output(da(
            "env", f"HOME={tmp_home_life.name}",
            "buildctl", "--addr", f'kube-pod://buildkitd-0?context={kube_context}',
            "build", "--frontend", "dockerfile.v0",
            "--local", f"context={out}", "--local", f"dockerfile={out}", #todo not context for commit
            *(("--opt", f"target={target}") if target else ()),
            *(("--opt", f"build-arg:C4COMMIT={commit}") if commit else ()),
            *(("--opt", f"build-arg:C4PROJECT={project}") if project else ()),
            "--output", f'type=image,name={from_image},push=true'
        ))
    to_image = f'{to_repo_opt or from_repo}:{image_tag}'
    if from_image != to_image:
        if only_source_repo: raise Exception("source deploy to alien repo is not allowed")
        check_output(da(*crane, "copy", from_image, to_image))
    print(to_image)

main()