
from __future__ import annotations
from json import dumps
from subprocess import check_call, check_output
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory

from util import debug_args, changing_text

def allure_generate(allure_results, s3conf_dir, bucket, project) -> None:
    keep_unpacked = 30
    life = TemporaryDirectory()
    tmp = life.name

    #setup rclone
    dst = f"ceph:{bucket}"
    report_path = f"{tmp}/allure-report"
    read_conf = lambda k: (Path(s3conf_dir)/k).read_bytes().decode().strip()
    rclone_config = f"{tmp}/rclone.conf"
    changing_text(rclone_config, "\n".join((
        '[ceph]', 'type = s3', 'provider = Ceph',
        f'access_key_id = {read_conf("key")}',
        f'secret_access_key = {read_conf("secret")}',
        f'endpoint = {read_conf("address")}',
    )))
    rclone = ["rclone","--config",rclone_config]
    lsf = lambda: sorted(check_output(debug_args("running", [*rclone, "lsf", dst])).decode().splitlines())
    call = lambda cmd: check_call(debug_args("running", cmd))
    copyto = lambda src, dst: call([*rclone, "copyto", src, dst])

    # generate with history
    hist = f"history-{project}.jsonl"
    local_hist, remote_hist = f"{tmp}/{hist}", f"{dst}/{hist}"
    conf_path = f"{tmp}/allurerc.json"
    conf = {"historyPath": local_hist, "appendHistory": True, "output": report_path}
    if hist in lsf(): copyto(remote_hist, local_hist)
    changing_text(conf_path, dumps(conf))
    call(["allure", "generate", allure_results, "--config", conf_path])
    copyto(local_hist, remote_hist)

    # upload report
    run_name = f"run.{datetime.now(timezone.utc):%Y-%m-%dT%H-%M-%SZ}.{project}"
    local_tgz = f"{tmp}/allure-report.tgz"
    call(["tar", "-czf", local_tgz, "-C", report_path, "."])
    call([*rclone, "copy", report_path, f"{dst}/{run_name}.unp"])
    copyto(local_tgz, f"{dst}/{run_name}.tgz")

    # purge old
    proj_unp = sorted(d for d in lsf() if d.startswith("run.") and d.endswith(f".{project}.unp/"))
    old = proj_unp[:-keep_unpacked]
    for d in old: call([*rclone, "purge", f"{dst}/{d}"])

    # generate and upload index
    local_index = f"{tmp}/index.html"
    js = (Path(__file__).parent/"allure.js").read_bytes().decode()
    changing_text(local_index, f"<!doctype html><script>const RUNS = {dumps(lsf())};{js}</script>")
    copyto(local_index, f"{dst}/index.html")
