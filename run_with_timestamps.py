
import sys
import subprocess
import time

script, *args = sys.argv
started = time.time()
with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
    for line in proc.stdout:
        m, s = divmod(int(time.time()-started), 60)
        print(f"{m}:{str(s).zfill(2)} {line}",end="")
    proc.wait()
sys.exit(proc.returncode)
