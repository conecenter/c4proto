
import os
import time
import subprocess
import sys


def log_arg(*a):
    print(f"running: {str(a)}", file=sys.stderr)
    return a


script, arg = sys.argv
if arg == "main":
    subprocess.run(log_arg(
        "git", "clone", "-b", os.environ["C4CI_PROTO_BRANCH"], "--depth", "1", "--",
        os.environ["C4CI_PROTO_REPO"], os.environ["C4CI_PROTO_DIR"]
    ), check=True)
    while True:
        started = time.monotonic()
        subprocess.run(log_arg("python3", "-u", script, '[["main"]]'), check=False)
        time.sleep(max(0., 20 - time.monotonic() + started))
else:
    sys.path.append(os.environ["C4CI_PROTO_DIR"])
    from c4util.cio import main
    main()
