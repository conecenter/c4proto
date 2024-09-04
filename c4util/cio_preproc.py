
import re
from json import dumps, loads

from . import never, never_if


def find_def(scope, name):
    found = [st[2:] for st in scope[0] if st[0] == "def" and st[1] == name]
    # noinspection PyTypeChecker
    return (
        find_def(scope[1], name) if len(found) < 1 and scope[1] is not None else
        (*(None, *found[0])[-2:], scope) if len(found) == 1 else never(f"non-single {name}")
    )


def plan_steps(scope): return [ps for step in scope[0] for ps in plan_step(scope, *step)]


def plan_step(scope, op, *step_args):
    if op == "def":
        return ()
    elif op == "for":
        items, body = step_args
        return [ps for it in items for ps in plan_steps((arg_substitute({"it": it}, body), scope))]
    elif op == "call":
        msg, = step_args
        name = msg["op"]
        args, c_scope, p_scope = find_def(scope, name)
        bad_args = [] if args is None else sorted(set(msg.keys()).symmetric_difference(["op", *args]))
        never_if([f"bad arg {arg} of {name}" for arg in bad_args])
        return plan_steps((arg_substitute(msg, c_scope), p_scope))
    else:
        return [(op, *step_args)]


def arg_substitute(args, body):
    patt = re.compile(r'\{(\w+)}|"@(\w+)"')
    repl = (lambda a: args.get(a.group(1), dumps(args[a.group(2)]) if a.group(2) in args else a.group(0)))
    return loads(patt.sub(repl, dumps(body)))