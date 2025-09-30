from subprocess import check_call
from pathlib import Path

cp = Path("/c4/kafka-clients-classpath").read_bytes().decode().strip()
check_call(("java", "--source", "21", "--enable-preview", "-cp", cp, "/app/main.java"))