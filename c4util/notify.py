
import re
import json
import subprocess
import sys
import http.client
from . import Popen, http_check, http_exchange, log


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


def notify_started(cmd, requests):
    notify_send_req(*requests["started"])
    proc = Popen(cmd, stdin=subprocess.PIPE, text=True)
    print(json.dumps(requests["failed"]), file=proc.stdin, flush=True)
    return lambda: print(json.dumps(requests["succeeded"]), file=proc.stdin, flush=True)


def notify_wait_finish():
    last_line = None
    for line in sys.stdin:
        last_line = line
    #log(last_line)
    notify_send_req(*json.loads(last_line))


def notify_send_req(host, method, url, data, headers):
    conn = http.client.HTTPSConnection(host, None)
    # log([host, method, url, data, headers])
    http_check(*http_exchange(conn, method, url, json.dumps(data).encode("utf-8"), headers))
