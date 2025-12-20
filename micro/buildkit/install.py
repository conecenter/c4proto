
from pathlib import Path
from subprocess import check_output
from sys import argv

check_output(("kubectl", "--context", argv[1], "apply", "-f", Path(__file__).parent/"k8s.yaml"))
