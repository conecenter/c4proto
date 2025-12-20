
import sys
from json import loads, dumps
from pathlib import Path

script, context, mod = sys.argv
plain_conf = loads(Path(f"{context}/c4dep.main.json").read_bytes().decode())
main_pub = [f"{context}/{line[2]}" for line in plain_conf if line[0] == "C4PUB" and line[1] == "main"]
print(dumps({
    "C4PUBLIC_PATH": ":".join((f"{context}/target/c4/client/out", *main_pub)),
    "C4MODULES": Path(f"{context}/target/c4/mod.{mod}.d/c4modules").read_bytes().decode(),
    "CLASSPATH": Path(f"{context}/target/c4/mod.{mod}.d/target/c4classpath").read_bytes().decode(),
}, sort_keys=True))
