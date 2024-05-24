from c4util import parse_table, read_text, changing_text, changing_text_observe, run
from c4util.build import need_dir, setup_parser


def handle_local(opt):
    rels = opt.rels.split(":")
    will_commits = " ".join(
        read_text(f"{opt.from_dir}/target/c4sync-head-{rel}").strip()
        for rel in rels
    )
    add_files = {
        (f"{rel}/{fn}" if rel else fn)
        for rel in rels
        for tp, fn
        in parse_table(read_text(f"{opt.from_dir}/target/c4sync-stat-{rel}"))
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
        ("local", handle_local, ("--from-dir", "--to-dir", "--rels")),
    )).parse_args()
    opt.op(opt)


main()
