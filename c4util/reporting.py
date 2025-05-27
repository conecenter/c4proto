
from typing import NamedTuple, Union, List
from queue import Queue
from socketserver import ThreadingTCPServer, StreamRequestHandler
from time import sleep
from json import dumps

ClientQueue = Queue[bytes]
class ReportingCheck(NamedTuple): pass
class ReportingSubscribe(NamedTuple):
    queue: ClientQueue
class ReportingUnsubscribe(NamedTuple):
    queue: ClientQueue
class ReportingData(NamedTuple):
    data: bytes
ReportingEvent = Union[ReportingCheck,ReportingSubscribe,ReportingUnsubscribe,ReportingData]

class ReportingServer(ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True

def init_reporting(addr, need_report):
    def checking():
        while True:
            broadcaster_q.put(ReportingCheck())
            sleep(1)
    def broadcasting():
        subscribers: List[ClientQueue] = []
        while True:
            match broadcaster_q.get():
                case ReportingCheck():
                    if subscribers: need_report()
                case ReportingData(data):
                    for q in subscribers: q.put(data)
                case ReportingSubscribe(q): subscribers.append(q)
                case ReportingUnsubscribe(q):
                    if q in subscribers: subscribers.remove(q)
    class ReportingHandler(StreamRequestHandler):
        def handle(self):
            client_q: ClientQueue = Queue()
            try:
                broadcaster_q.put(ReportingSubscribe(client_q))
                while True:
                    msg = client_q.get()
                    self.wfile.write(msg)
                    self.wfile.flush()
            finally: broadcaster_q.put(ReportingUnsubscribe(client_q))
    def serving():
        # noinspection PyTypeChecker
        ReportingServer(addr, ReportingHandler).serve_forever()
    def send(report): broadcaster_q.put(ReportingData(f'{dumps(report)}\n'.encode()))
    broadcaster_q: Queue[ReportingEvent] = Queue()
    tasks = (checking, broadcasting, serving)
    return tasks, send
