
import tempfile
from . import run, run_no_die


def git_clone(repo, branch):
    dir_life = tempfile.TemporaryDirectory()
    run(("git", "clone", "-b", branch, "--depth", "1", "--", repo, "."), cwd=dir_life.name)
    return dir_life


def git_init(repo):
    dir_life = tempfile.TemporaryDirectory()
    run(("git", "init"), cwd=dir_life.name)
    run(("git", "remote", "add", "origin", repo), cwd=dir_life.name)
    return dir_life


def git_add_tagged(d, tag):
    run(("git", "add", "."), cwd=d)
    run(("git", "config", "user.email", "ci@c4proto"), cwd=d)
    run(("git", "config", "user.name", "ci@c4proto"), cwd=d)
    run(("git", "commit", "-m-"), cwd=d)
    run(("git", "tag", tag), cwd=d)
    run_no_die(("git", "push", "--delete", "origin", tag), cwd=d)
    run(("git", "push", "--tags", "origin"), cwd=d)
