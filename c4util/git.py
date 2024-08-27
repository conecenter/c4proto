
from . import run, run_no_die, run_text_out, log, never


def git_init(repo, d):
    run(("git", "init"), cwd=d)
    run(("git", "remote", "add", "origin", repo), cwd=d)


def git_fetch_checkout(branch, d, fetch_can_fail):
    fetched = run_no_die(("git", "fetch", "--depth", "1", "-k", "--", "origin", branch), cwd=d)
    from_br = ["FETCH_HEAD"] if fetched else [] if fetch_can_fail else never("fetch fail")
    run(("git", "checkout", "-b", branch, *from_br), cwd=d)


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
