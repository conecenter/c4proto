
import sys
from json import loads, dumps
from c4util import read_text

script, context, mod = sys.argv
plain_conf = loads(read_text(f"{context}/c4dep.main.json"))
main_pub = [f"{context}/{line[2]}" for line in plain_conf if line[0] == "C4PUB" and line[1] == "main"]
print(dumps({
    "C4PUBLIC_PATH": ":".join((f"{context}/target/c4/client/out", *main_pub)),
    "C4MODULES": read_text(f"{context}/target/c4/mod.{mod}.d/c4modules"),
    "CLASSPATH": read_text(f"{context}/target/c4/mod.{mod}.d/target/c4classpath"),
}, sort_keys=True))
