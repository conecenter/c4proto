
import sys
import subprocess
import asyncio

loop = asyncio.get_event_loop()
max_proc = int(sys.argv[1])
def iteration(was_running, to_run):
    for process in was_running:
        returncode = process.poll()
        if returncode != None and returncode != 0:
            raise Exception("task failed: "+process.args)
    running  = [process for process in was_running if process.returncode == None]
    new_start_count = max_proc - len(running)
    to_run_now   = to_run[:new_start_count]
    to_run_later = to_run[new_start_count:]
    will_running = running + [subprocess.Popen(cmd, shell=True) for cmd in to_run_now]
    print("running: "+len(will_running)+", waiting: "+len(to_run_later))
    if len(will_running)>0 or len(to_run_later)>0:
        loop.call_later(1,iteration,will_running,to_run_later)
    else:
        loop.stop()
iteration([], [line for line in sys.stdin])
loop.run_forever()

#main_future = loop.create_future()
#main_future.set_result(True)
#loop.run_until_complete(main_future)
