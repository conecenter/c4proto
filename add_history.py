
from argparse import ArgumentParser
from tempfile import TemporaryDirectory
from c4util import path_exists
from c4util.build import never, secret_part_to_text, run_no_die, run, run_text_out


def main():
    parser = ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--message", required=True)
    opt = parser.parse_args()
    if not path_exists(opt.context) or path_exists(f"{opt.context}/.git"):
        never("bad context")
    repo = secret_part_to_text("c4out-repo/value")
    with TemporaryDirectory() as temp_root:
        if run_no_die(("git", "clone", "-b", opt.branch, "--depth", "1", "--", repo, "."), cwd=temp_root):
            run(("mv", f"{temp_root}/.git", opt.context))
        else:
            run(("git", "init"), cwd=opt.context)
            run(("git", "remote", "add", "origin", repo), cwd=opt.context)
            run(("git", "checkout", "-b", opt.branch), cwd=opt.context)
    run(("git", "add", "."), cwd=opt.context)
    run(("git", "config", "user.email", "ci@c4proto"), cwd=opt.context)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=opt.context)
    if run_no_die(("git", "commit", "-m", opt.message), cwd=opt.context):
        run(("git", "push", "--set-upstream", "origin", opt.branch), cwd=opt.context)
    elif len(run_text_out(("git", "status", "--porcelain=v1"), cwd=opt.context).strip()) > 0:
        never("can not commit")
    else:
        print("unchanged")


main()
