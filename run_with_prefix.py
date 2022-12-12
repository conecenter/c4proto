
import sys
import subprocess
import time

def get_time_str():
    m, s = divmod(int(time.monotonic()-started), 60)
    return f"{m}:{str(s).zfill(2)}"

script, prefix, *args = sys.argv
started = time.monotonic()
get_prefix = get_time_str if prefix == "time" else lambda: prefix
with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
    for line in proc.stdout:
        print(f"{get_prefix()} {line}",end="")
    proc.wait()
sys.exit(proc.returncode)
