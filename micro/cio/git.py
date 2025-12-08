
from util import run, run_no_die, run_text_out, log, never_if


def git_init(repo, d):
    run(("git", "init"), cwd=d)
    run(("git", "remote", "add", "origin", repo), cwd=d)


def git_clone(repo, branch, d):
    run(("git", "clone", "--depth", "1", "-b", branch, "--", repo, d ))


def git_pull(d):
    branch = run_text_out(("git", "branch", "--show-current"), cwd=d).strip()
    never_if(None if branch else "not a branch")
    run(("git", "pull", "origin", branch), cwd=d)


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


def git_clone_or_init(repo, branch, d):
    if not run_no_die(("git", "clone", "--depth", "1", "-b", branch, "--", repo, d)):
        git_init(repo, d)
        run(("git", "checkout", "-b", branch), cwd=d)
        git_set_user(d)
        run(("git", "commit", "-m-", "--allow-empty"), cwd=d)
        run(("git", "push", "--set-upstream", "origin", branch), cwd=d)


def git_save_changed(d):
    if len(run_text_out(("git", "status", "--porcelain=v1"), cwd=d).strip()) > 0:
        git_set_user(d)
        run(("git", "add", "."), cwd=d)
        run(("git", "commit", "-m-"), cwd=d)
        run(("git", "push"), cwd=d)
    else:
        log("unchanged")
