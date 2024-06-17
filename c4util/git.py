
from . import run, run_no_die, run_text_out, log


def git_clone(repo, branch, d): run(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=d)


def git_init(repo, d):
    run(("git", "init"), cwd=d)
    run(("git", "remote", "add", "origin", repo), cwd=d)


def git_clone_or_init(repo, branch, d):
    if not run_no_die(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=d):
        git_init(repo, d)
        run(("git", "checkout", "-b", branch), cwd=d)


def git_set_user(d):
    run(("git", "config", "user.email", "ci@c4proto"), cwd=d)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=d)


def git_add_tagged(d, tag):
    git_set_user(d)
    run(("git", "add", "."), cwd=d)
    run(("git", "commit", "-m-"), cwd=d)
    run(("git", "tag", tag), cwd=d)
    run_no_die(("git", "push", "--delete", "origin", tag), cwd=d)
    run(("git", "push", "--tags", "origin"), cwd=d)


def git_save_changed(context):
    if len(run_text_out(("git", "status", "--porcelain=v1"), cwd=context).strip()) > 0:
        git_set_user(context)
        run(("git", "add", "."), cwd=context)
        branch = run_text_out(("git", "rev-parse", "--abbrev-ref", "HEAD"), cwd=context).strip()
        run_no_die(("git", "pull", "origin", branch), cwd=context)
        run(("git", "commit", "-m-"), cwd=context)
        run(("git", "push", "--set-upstream", "origin", branch), cwd=context)
    else:
        log("unchanged")
