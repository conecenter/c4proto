
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


def plan_steps(scope, planned):
    for op, *step_args in scope[0]:
        if op == "def":
            pass
        elif op == "for":
            items, body = step_args
            for it in items:
                planned = plan_steps((arg_substitute({"it": it}, body), scope), planned)
        elif op == "call":
            msg, = step_args
            name = msg["op"]
            args, c_scope, p_scope = find_def(scope, name)
            bad_args = [] if args is None else sorted(set(msg.keys()).symmetric_difference(["op", *args]))
            never_if([f"bad arg {arg} of {name}" for arg in bad_args])
            planned = plan_steps((arg_substitute(msg, c_scope), p_scope), planned)
        elif op == "call_once": # not fair, by name only
            name, = step_args
            if not any(s for s in planned if s[0] == "called" and s[1] == name):
                args, c_scope, p_scope = find_def(scope, name)
                never_if(None if args == [] else f"bad args of {name}")
                planned = plan_steps((c_scope, p_scope), (*planned, ("called", name)))
        else:
            planned = (*planned, (op, *step_args))
    return planned


def arg_substitute(args, body):
    patt = re.compile(r'\{(\w+)}|"@(\w+)"')
    repl = (lambda a: args.get(a.group(1), dumps(args[a.group(2)]) if a.group(2) in args else a.group(0)))
    return loads(patt.sub(repl, dumps(body)))