
from dataclasses import dataclass
from functools import partial
from itertools import groupby
from typing import Callable, Any

class Export: pass

@dataclass(frozen=True, kw_only=True, slots=True)
class Op(Export): path: str; fn: Callable; is_command: bool
def rget(path: str) -> Callable[[Callable[..., Any]], Op]: return lambda fn: Op(path=path, fn=fn, is_command=False)
def post(path: str) -> Callable[[Callable[..., Any]], Op]: return lambda fn: Op(path=path, fn=fn, is_command=True)

@dataclass(frozen=True, kw_only=True, slots=True)
class Watch(Export): fn: Callable
def watch(*args): return Watch(fn=partial(*args))

def die(t): raise Exception(t) if isinstance(t, str) else t

def grouped(l): return [(k,[v for _,v in kvs]) for k,kvs in groupby(sorted(l, key=lambda kv: kv[0]), lambda kv: kv[0])]
