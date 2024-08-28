
from . import run, run_no_die, run_text_out, log


def git_init(repo, d):
    run(("git", "init"), cwd=d)
    run(("git", "remote", "add", "origin", repo), cwd=d)


def git_fetch_checkout(branch, d):
    run(("git", "fetch", "--depth", "1", "-k", "--", "origin", branch), cwd=d)
    run(("git", "checkout", "FETCH_HEAD"), cwd=d)


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


def git_fetch_checkout_or_create(branch, d):
    if run_no_die(("git", "fetch", "--depth", "1", "-k", "--", "origin", branch), cwd=d):
        run(("git", "checkout", branch))
    else:
        run(("git", "checkout", "-b", branch))
        git_set_user(d)
        run(("git", "commit", "-m-", "--allow-empty"), cwd=d)
        run(("git", "push", "--set-upstream", "origin", branch), cwd=d)


def git_save_changed(fr, d):
    branch = run_text_out(("git", "rev-parse", "--abbrev-ref", "HEAD"), cwd=d).strip()
    run(("git", "pull", "origin", branch), cwd=d)
    run(("rsync", "-acr", "--exclude", ".git", f"{fr}/", f"{d}/"))
    if len(run_text_out(("git", "status", "--porcelain=v1"), cwd=d).strip()) > 0:
        git_set_user(d)
        run(("git", "add", "."), cwd=d)
        run(("git", "commit", "-m-"), cwd=d)
        run(("git", "push"), cwd=d)
    else:
        log("unchanged")
