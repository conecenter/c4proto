
import sys
import subprocess
import json
import math
import time

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def run(args):
    print("running: "+" ".join(args))
    return subprocess.run(args,check=True,capture_output=True,text=True).stdout

def parse_table(data):
    return [line.split() for line in data.split("\n") if len(line) > 0]

def apply_header(head, body_lines):
    return [dict(zip(head,line)) for line in body_lines]

def parse_top(data):
    head, *body_lines = parse_table(data)
    return group_map(apply_header(head, body_lines), lambda d: (d["NAME"], d))

def int_unit(units,s):
    for unit, scale in units:
        if s.endswith(unit):
            return int(s[:len(s)-len(unit)])*scale
    raise Exception(s)

def col_sum(units,col_name,rows):
    return sum([int_unit(units,row[col_name]) for row in rows if row])

def get_pods(context_name):
    kc = get_kc(context_name)
    cpu_units = [("m",1),("",1000)]
    mem_units = [("Gi",1024),("Mi",1)]
    top_pods = parse_top(run([*kc,"top","pods"]))
    return [
        {
            "context_name": context_name,
            "pod_name": pod_name,
            "deployment_name": deployment_name,
            "is_de_main": deployment_name.startswith("de-") and deployment_name.endswith("-main"),
            "node_name": pod["spec"]["nodeName"],
            "req_cpu": col_sum(cpu_units,"cpu",requests),
            "req_mem": col_sum(mem_units,"memory",requests),
            "top_cpu": col_sum(cpu_units,"CPU(cores)",tops),
            "top_mem": col_sum(mem_units,"MEMORY(bytes)",tops),
        }
        for pod in json.loads(run([*kc,"get","pods","-o","json"]))["items"]
        for pod_name in [pod["metadata"]["name"]]
        for deployment_name in [get_deployment_name(pod_name)] if deployment_name
        for requests in [[
            container["resources"].get("requests")
            for container in pod["spec"]["containers"]
        ]]
        for tops in [top_pods.get(pod_name,[])]
    ]

def get_nodes(context_name):
    pods = get_pods(context_name)
    p_units = [("%",1)]
    top_nodes = parse_top(run([*get_kc(context_name),"top","nodes"]))
    return [
        {
            "node_name": node_name,
            "top_cpu_p": col_sum(p_units, "CPU%", tops),
            "top_mem_p": col_sum(p_units, "MEMORY%", tops),
            "pods": pods
        }
        for node_name, pods in group_map(pods, lambda d: (d["node_name"], d)).items()
        for tops in [top_nodes.get(node_name,[])]
    ]

def get_sorted_nodes(nodes,top_p_k,top_k):
    return [
        { **node, "pods": sorted(node["pods"],key=top_k,reverse=True) }
        for node in sorted(nodes,key=top_p_k,reverse=True)
    ]

def get_kc(context_name): return ("kubectl","--context",context_name)

def get_filtered_nodes(nodes,search_str):
    return [n for n in nodes if any(search_str in p["pod_name"] for p in n["pods"])]

def handle_top(context_name,search_str):
    nodes = get_filtered_nodes(get_nodes(context_name), search_str)
    for hint, top_p_k, top_k, req_k in (("CPU","top_cpu_p","top_cpu","req_cpu"),("MEMORY","top_mem_p","top_mem","req_mem")):
        print(hint)
        sorted_nodes = get_sorted_nodes(nodes, lambda node: node[top_p_k], lambda pod: pod[top_k])
        for node in sorted_nodes:
            print(f"  {node[top_p_k]}% "+node["node_name"])
            for c in node["pods"]:
                print(f"    {c[top_k]} of {c[req_k]}  {c['pod_name']}")

def get_deployment_name(pod_name):
    parts = pod_name.split("-")
    return "-".join(parts[0:5]) if len(parts)==7 else None

def suggest(nodes):
    for node in get_sorted_nodes(nodes, lambda node: node["top_mem_p"], lambda pod: pod["top_mem"]-pod["req_mem"]):
        for pod in node["pods"]:
            if pod["top_mem"]>pod["req_mem"]:
                requests = f"memory={math.ceil(pod['top_mem']/1024)}Gi"
                return (pod["deployment_name"],requests,node["top_mem_p"])

def get_top_mem_nodes(context_name,level):
    return [n for n in get_nodes(context_name) if n["top_mem_p"] > level]

def handle_suggest(context_name,level):
    suggested = suggest(get_top_mem_nodes(context_name,int(level)))
    if suggested:
        deployment_name, requests, top_mem_p = suggested
        cmd = get_set_cmd(context_name, deployment_name, requests)
        print("suggest:\n"+" ".join(cmd))

def get_set_cmd(context_name,deployment_name,requests):
    kc = get_kc(context_name)
    return [*kc,"set","resources",f"--requests={requests}","deployment",deployment_name]

def handle_set(context_name,deployment_name,requests):
    print(run(get_set_cmd(context_name,deployment_name,requests)))

def loop(inner):
    def run(period_str, *args):
        period = int(period_str)
        state = None
        while True:
            print("### step")
            try:
                state = inner(args,state)
            except subprocess.CalledProcessError as err:
                print(err)
            time.sleep(period)
    return run

def get_exec_cmd(pod):
    kc = get_kc(pod["context_name"])
    return (*kc,"exec",pod["pod_name"],"--")

def ps_java(pod):
    head, *body_lines = \
        parse_table(run((*get_exec_cmd(pod),"ps","-eo","pid,etimes,args")))
    return [
        (proc, proc["1stARG"]=="ee.cone.c4actor.ServerMain")
        for proc in apply_header([*head,"1stARG"],body_lines)
        if proc["COMMAND"] == "java"
    ]

def iter_de_purger(args,state): #60
    context_name, age_sec_str = args
    age_sec = int(age_sec_str)
    des = [pod for pod in get_pods(context_name) if pod["is_de_main"]]
    for pod in des:
        killable = [proc for proc, is_c4 in ps_java(pod) if is_c4]
        for proc in killable:
            print("killable: "+proc["PID"])
        to_kill = [
            proc["PID"] for proc in killable
            if int(proc["ELAPSED"]) > age_sec
        ]
        if to_kill:
            run((*get_exec_cmd(pod),"kill",*to_kill))

def iter_gc_runner(args,state): #25
    context_name, period_str, level_str = args
    period = int(period_str)
    lev = int(level_str)
    gc_tasks = [
        (*get_exec_cmd(pod),"jcmd",proc["PID"],"GC.run")
        for node in get_top_mem_nodes(context_name,lev)
        for pod in node["pods"]
        for proc, is_c4 in ps_java(pod)
    ]
    if not gc_tasks:
        return
    for task in gc_tasks:
        run(task)
    time.sleep(period)

def iter_req_setter(args,state): #10
    context_name, period_str, yellow_lev_str, red_lev_str = args
    period = int(period_str)
    yellow_lev = int(yellow_lev_str)
    red_lev = int(red_lev_str)
    suggested = suggest(get_top_mem_nodes(context_name,yellow_lev))
    if not suggested:
        return
    deployment_name, requests, top_mem_p = suggested
    print(f"suggested: {deployment_name} {requests} {top_mem_p}%")
    was_deployment_name, started = state or (deployment_name, time.time())
    is_same = was_deployment_name == deployment_name
    is_suggested_constantly = is_same and time.time()-started > period
    if is_suggested_constantly or top_mem_p > red_lev:
        run(get_set_cmd(context_name, deployment_name, requests))
    else:
        return (deployment_name, started)

handle = {
    "top": handle_top,
    "suggest": handle_suggest,
    "set": handle_set,
    "de_purger": loop(iter_de_purger),
    "gc_runner": loop(iter_gc_runner),
    "req_setter": loop(iter_req_setter),
}

script, act, *args = sys.argv
handle[act](*args)
