
from pathlib import Path
from subprocess import check_output
from sys import argv

check_output(("kubectl", "--context", argv[1], "apply", "-f", str(Path(__file__).parent/"statefulset.userns.yaml")))
