
import re
from json import dumps
import http.client
from pathlib import Path

from . import http_check, http_exchange, changing_text, read_json, run
from .cmd import get_cmd


# immutable, api specific:
def notify_create_requests(auth, full_url, now, work_hours, valid_hours, descr):
    host, url = re.fullmatch(r'https://([^/]+)(.*)', full_url).group(1, 2)
    headers = {"Authorization": auth, "Content-Type": "application/json"}
    return {
        status: [host, "PUT", url, {
            "status": status, "description": descr,
            "due_date": int(now*1000)+int(hours)*60*60*1000, "due_date_time": True,
        }, headers]
        for status, hours in (("started", work_hours), ("succeeded", valid_hours), ("failed", 0))
    }


# general:

#todo fix fin-ion
def notify_started(get_dir, requests):
    notify_send_req(*requests["started"])
    changing_text(get_dir("fin.json"), dumps(get_cmd(notify_send_req, *requests["failed"])))
    changing_text(get_dir("notify_succeeded.json"), dumps(get_cmd(notify_send_req, *requests["succeeded"])))


def notify_succeeded(get_dir):
    run(read_json(get_dir("notify_succeeded.json")))
    Path(get_dir("fin.json")).unlink()


def notify_send_req(host, method, url, data, headers):
    conn = http.client.HTTPSConnection(host, None)
    # log([host, method, url, data, headers])
    http_check(*http_exchange(conn, method, url, dumps(data).encode("utf-8"), headers))
