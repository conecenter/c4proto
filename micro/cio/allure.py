
from __future__ import annotations
from json import dumps
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from concurrent.futures import ThreadPoolExecutor
from itertools import groupby
from re import fullmatch

from util import changing_text, read_text, run_text_out, run as call, log

def grouped(l): return [(k, [v for _, v in kvs]) for k, kvs in groupby(sorted(l, key=lambda kv: kv[0]), lambda kv: kv[0])]

def allure_generate(allure_results, s3conf_dir, bucket, project) -> None:
    life = TemporaryDirectory()
    tmp = life.name
    #
    keep_unpacked = 30

    #setup rclone
    dst = f"ceph:{bucket}"
    read_conf = lambda k: read_text(f"{s3conf_dir}/{k}").strip()
    rclone_config = f"{tmp}/rclone.conf"
    changing_text(rclone_config, "\n".join((
        '[ceph]', 'type = s3', 'provider = Ceph',
        f'access_key_id = {read_conf("key")}',
        f'secret_access_key = {read_conf("secret")}',
        f'endpoint = {read_conf("address")}',
    )))
    rclone = ["rclone","--config",rclone_config]
    lsf = lambda p: sorted(run_text_out([*rclone, "lsf", p]).splitlines())
    lsf_runs = lambda: [d for d in lsf(dst) if d.startswith("run.") and d.endswith(f".{project}.unp/")]
    copyto = lambda fr, to: call([*rclone, "copyto", fr, to])

    # generate with history
    report_path = f"{tmp}/allure-report"
    hist = f"history-{project}.jsonl"
    local_hist, remote_hist = f"{tmp}/{hist}", f"{dst}/{hist}"
    conf_path = f"{tmp}/allurerc.json"
    conf = {"historyPath": local_hist, "appendHistory": True, "output": report_path}
    if hist in lsf(dst): copyto(remote_hist, local_hist)
    changing_text(conf_path, dumps(conf))
    call(["allure", "generate", allure_results, "--config", conf_path])
    copyto(local_hist, remote_hist)

    # patch navigation
    js_content = read_text(str(Path(__file__).parent/"allure.js"))
    patch_content = f'<script>\nconst c4proj={dumps(project)}\n{js_content}\n</script>'
    index_path = f"{report_path}/index.html"
    changing_text(index_path, read_text(index_path).replace('</body>', f'{patch_content}</body>'))

    # upload report
    run_name = f"run.{datetime.now(timezone.utc):%Y-%m-%dT%H-%M-%SZ}.{project}"
    local_tgz = f"{tmp}/allure-report.tgz"
    call(["tar", "-czf", local_tgz, "-C", report_path, "."])
    call([*rclone, "copy", report_path, f"{dst}/{run_name}.unp"])
    copyto(local_tgz, f"{dst}/{run_name}.tgz")

    # purge old
    for old in lsf_runs()[:-keep_unpacked]: call([*rclone, "purge", f"{dst}/{old}"])

    # make links
    lsf_run = lambda r: (r, lsf(f'{dst}/{r}data/test-results'))
    grouped_links = grouped(
        (m.group(1), f'../{r}index.html')
        for r, its in ThreadPoolExecutor(max_workers=8).map(lsf_run, lsf_runs())
        for it in its
        for m in [fullmatch(pattern=r"([0-9a-f]+)\.json", string=it)] if m
    )
    duplicate_links = { k for k, vs in grouped_links if len(vs) > 1 }
    if duplicate_links: log(f"warn duplicate id in history: {duplicate_links}")
    links = {k: vs[-1] for k, vs in grouped_links}
    links_path = f"{tmp}/c4-history-links.json"
    changing_text(links_path, dumps(links, sort_keys=True))
    copyto(links_path, f"{dst}/c4-history-links-{project}.json")

    # KUI owns the report list UI now. CIO only produces immutable Allure artifacts
    # so the tab can list/render them through the authenticated S3 proxy.
