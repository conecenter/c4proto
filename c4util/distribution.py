
from itertools import islice
from functools import reduce
from time import monotonic
from json import dumps
from typing import NamedTuple
from logging import info, warning

from . import group_map

class Event(NamedTuple):
    group: str
    task: str
    status: str
    time: float

def distribution_calc(groups, task_list, try_count, check_task, events: list[Event]):
    last_res = (lambda d, k: d[k][-1] if k in d else "F")
    # tasks: Processing, Succeeded, Failed, FinallyFailed
    # groups: Processing, Succeeded, Failed
    t2rs = group_map(events, lambda ev: (ev.task, ev.status))
    g2rs = group_map(events, lambda ev: (ev.group, ev.status))
    last_r2ts = group_map(task_list, lambda t: (last_res(t2rs, t), t))
    last_r2gs = group_map(groups   , lambda g: (last_res(g2rs, g), g))
    check_to_starts = [(g, check_task) for g in last_r2gs.get("F",())]
    t2rsp = group_map((ev.task for ev in events if ev.status == "P"), lambda t: (t, True))
    get_try_count = (lambda t: len(t2rsp[t]) if t in t2rsp else 0)
    todo_tasks = sorted((t for t in last_r2ts.get("F",()) if get_try_count(t) < try_count), key=get_try_count)
    started_set = {(ev.group, ev.task) for ev in events}
    was_task = (lambda starts, g, t: (g, t) in started_set or any(t==t0 for g0, t0 in starts))
    find_starts = (lambda starts, g: islice(((g, t) for t in todo_tasks if not was_task(starts, g, t)), 0, 1))
    task_to_starts = reduce(lambda starts, g: (*starts, *find_starts(starts, g)), last_r2gs.get("S",()), ())
    finally_failed = None if "P" in last_r2ts or todo_tasks else last_r2ts.get("F",())
    return (*check_to_starts, *task_to_starts), finally_failed, len(todo_tasks)


def distribution_run(groups, task_list, try_count, check_task, do_start, do_get):
    events: list[Event] = []
    started_at = monotonic()
    get_time = lambda: monotonic() - started_at
    while True:
        to_starts, finally_failed, todo_count = distribution_calc(groups, task_list, try_count, check_task, events)
        info(f"todo: {todo_count}")
        for group, task in to_starts:
            events.append(Event(group, task, "P", get_time()))
            do_start(group, task)
        if finally_failed is None:
            ok, group, task = do_get()
            events.append(Event(group, task, "S" if ok else "F", get_time()))
        else:
            warning(f'todo: {dumps(finally_failed)}')
            info("\n".join(f"distribution was {ev.status} {ev.group} {ev.task} {ev.time}" for ev in events))
            break
