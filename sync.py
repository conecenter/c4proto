from c4util import parse_table, read_text, changing_text, changing_text_observe
from build_util import never, need_dir, run, setup_parser


def get_sync_rels(replink):
    return ("", *(
        local
        for cmd, local_str, rem, comm in parse_table(read_text(replink))
        for local, dummy in (local_str.split("/"),)
        if cmd == "C4REL" and dummy == "dummy" or never("bad rel")
    )), f"target/c4sync-head-", f"target/c4sync-stat-"


def handle_gen(opt):
    rels, head_path, stat_path = get_sync_rels(opt.replink)
    git_lines = [line for rel in rels for line in (
        f"git -C ./{rel} rev-parse HEAD > {head_path}{rel}",
        f"git -C ./{rel} status --porcelain=v1 --no-renames > {stat_path}{rel}",
    )]
    need_dir(f"{opt.from_dir}/target")
    changing_text(f"{opt.from_dir}/target/c4sync.bat", "\n".join((
        f"docker exec -e C4REPO_MAIN_CONF={opt.replink} c4agent_kc /replink.pl",
        *git_lines,
        "docker exec c4agent_kc sh -c '" +
        "python3 -u $C4CI_PROTO_DIR/sync.py local" +
        f" --from-dir {opt.from_dir}" +
        f" --to-dir {opt.to_dir}" +
        f" --replink {opt.replink}" +
        " && c4sync_remote" +
        "'",
    )))


def handle_local(opt):
    rels, head_path, stat_path = get_sync_rels(opt.replink)
    will_commits = " ".join(
        read_text(f"{opt.from_dir}/{head_path}{rel}").strip() for rel in rels
    )
    add_files = {
        (f"{rel}/{fn}" if rel else fn)
        for rel in rels
        for tp, fn in parse_table(read_text(f"{opt.from_dir}/{stat_path}{rel}"))
    }
    to_target_dir = f"{opt.to_dir}/target"
    commits_path = f"{to_target_dir}/c4sync-commits"
    a_path = f"{to_target_dir}/c4sync-acc"
    rsync_pre = (
        "rsync", "-acr", "--del", "--exclude", ".git/", "--exclude", "target/"
    )
    from_to = (f"{opt.from_dir}/", opt.to_dir)
    for save in changing_text_observe(commits_path, will_commits):
        run((*rsync_pre, *from_to))
        need_dir(to_target_dir)
        changing_text(a_path, "")
        save()
    was_files = read_text(a_path).splitlines()
    changing_text(a_path, "\n".join(sorted({*was_files, *add_files})))
    run((
        *rsync_pre, "-v", "--files-from", a_path, "--delete-missing-args",
        *from_to
    ))


def main():
    opt = setup_parser((
        ("gen", handle_gen, ("--from-dir", "--to-dir", "--replink")),
        ("local", handle_local, ("--from-dir", "--to-dir", "--replink")),
    )).parse_args()
    opt.op(opt)


main()

