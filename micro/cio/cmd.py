
from sys import argv
from os import environ
from json import dumps, loads
from importlib import import_module
from logging import basicConfig, DEBUG


def get_cmd(f, *args): return (
    "python3", "-u", "-c", "from cmd import run_cmd as f;f()",
    dumps([f.__module__, f.__name__,*(("env",*args[1:]) if args and args[0] is environ else ("",*args))])
)


def run_cmd():
    mod, fun, pre_arg, *args = loads(argv[1])
    basicConfig(level=DEBUG)
    getattr(import_module(mod), fun)(*([environ] if pre_arg=="env" else()),*args)
