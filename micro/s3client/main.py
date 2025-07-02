
from subprocess import check_call
from pathlib import Path
from os import environ
from socket import create_connection
from sys import argv

def one(it): return it

def never(a): raise Exception(a)

def read_conf(key): return (Path(environ["C4S3_CONF_DIR"]) / key).read_bytes().decode()

def kafka_port(): return 9000

def serve(_):
    check_call(("/tools/mc", "alias", "set", "def", read_conf("address"), read_conf("key"), read_conf("secret")))
    cp = Path("/c4/kafka-clients-classpath").read_bytes().decode().strip()
    check_call(("java", "--source", "21", "--enable-preview", "-cp", cp, "/app/kafka.java", str(kafka_port())))

def sock_exchange_init(sock):
    file = sock.makefile('r', encoding='utf-8', newline='\n')
    def exchange(msg: bytes, need_resp: str):
        sock.sendall(msg + b'\n')
        resp_tp, *resp_args = file.readline().split()
        return resp_args if resp_tp == need_resp else never(f"bad resp: {resp_tp} {resp_args}")
    return exchange

def produce_one(topic, message):
    with create_connection(("127.0.0.1",kafka_port())) as sock:
        exchange = sock_exchange_init(sock)
        exchange(f'PRODUCE {topic}'.encode(), "OK")
        print(one(*exchange(message.encode(), "ACK")))

{ "serve": serve, "produce_one": produce_one }[argv[1]](*argv[2:])
