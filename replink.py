from argparse import ArgumentParser
from json import loads
from pathlib import Path
from subprocess import check_output, Popen, DEVNULL
from sys import stderr
from tempfile import TemporaryDirectory
from re import fullmatch
from time import time

def da(*args):
    print(f"[{time()}]starting: " + " ".join(args), file=stderr)
    return args

def get_plain_options(plain_conf, k): return [line[2] for line in plain_conf if line[0] == k]

def main():
    parser = ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--set-proto-dir", required=False)
    parser.add_argument("--commit", required=False)
    parser.add_argument("--commits-out", required=False)
    args = parser.parse_args()
    context = Path(args.context)
    #
    plain_conf = loads((context/"c4dep.main.json").read_bytes())
    replink, = get_plain_options(plain_conf, "C4REPLINK")
    proto_postfix, = get_plain_options(plain_conf, "C4PROTO_POSTFIX")
    #
    lines = [l.strip() for l in (context/replink).read_bytes().decode().splitlines() if l.strip()]
    active_lines = [l for l in lines if not l.startswith("#")]
    rels = [fullmatch(r"C4REL\s+(\w+)/\w+\s+(https:.*)\.git\s+(\w+)",l.strip()).groups() for l in active_lines]
    #
    tmp_life = TemporaryDirectory()
    tmp = Path(tmp_life.name)
    dl_proc = [(k, Popen(da("curl","-L",f'{url}/archive/{ref}.zip',"-o",str(tmp/f'{k}.zip')))) for k, url, ref in rels]
    for k, proc in dl_proc:
        if proc.wait(30) != 0: raise Exception()
        check_output(da("unzip", str(tmp/f'{k}.zip'), "-d", str(tmp/k)), stderr=DEVNULL)
        for p in (tmp/k).iterdir(): p.rename(context/k)
    #
    if args.commits_out:
        commit = [f":{args.commit}"] if args.commit else []
        c_data = " ".join((*commit, *[f"{k}/:{ref}" for k, url, ref in rels])).encode()
        c_out = Path(args.commits_out)
        c_out.parent.mkdir(parents=True,exist_ok=True)
        c_out.write_bytes(c_data)
    if args.set_proto_dir: Path(args.set_proto_dir).symlink_to(context/proto_postfix)

main()