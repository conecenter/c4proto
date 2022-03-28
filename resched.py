
import subprocess
import json

def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def run(args):
    return subprocess.run(args,check=True,capture_output=True,text=True).stdout

def parse_top(data):
    head_line, *body_lines = data.split("\n")
    head = head_line.split()
    res = [dict(zip(head,line.split())) for line in body_lines if len(line) > 0]
    return group_map(res, lambda d: (d["NAME"], d))

def int_unit(units,s):
    for unit, scale in units:
        if s.endswith(unit):
            return int(s[:len(s)-len(unit)])*scale
    raise Exception(s)

def col_sum(units,col_name,rows):
    return sum([int_unit(units,row[col_name]) for row in rows if row])

p_units = [("%",1)]
cpu_units = [("m",1),("",1000)]
mem_units = [("Gi",1024),("Mi",1)]


def get_nodes(kc):
    top_pods = parse_top(run([*kc,"top","pods"]))
    top_nodes = parse_top(run([*kc,"top","nodes"]))
    pods = [
        {
            "pod_name": pod_name,
            "node_name": pod["spec"]["nodeName"],
            "req_cpu": col_sum(cpu_units,"cpu",requests),
            "req_mem": col_sum(mem_units,"memory",requests),
            "top_cpu": col_sum(cpu_units,"CPU(cores)",tops),
            "top_mem": col_sum(mem_units,"MEMORY(bytes)",tops),
        }
        for pod in json.loads(run([*kc,"get","pods","-o","json"]))["items"]
        for pod_name in [pod["metadata"]["name"]]
        for requests in [[
            container["resources"].get("requests")
            for container in pod["spec"]["containers"]
        ]]
        for tops in [top_pods.get(pod_name,[])]
    ]
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

kc = ("kubectl","--context","dev")
nodes = get_nodes(kc)
for hint, top_p_k, top_k, req_k in (("CPU","top_cpu_p","top_cpu","req_cpu"),("MEMORY","top_mem_p","top_mem","req_mem")):
    print(hint)
    sorted_nodes = get_sorted_nodes(nodes,lambda node: node[top_p_k], lambda pod: pod[top_k])
    for node in sorted_nodes:
        print(f"  {node[top_p_k]}% "+node["node_name"])
        for c in node["pods"]:
            print(f"    {c[top_k]} of {c[req_k]}  {c['pod_name']}")


def get_deployment_name(pod_name):
    parts = pod_name.split("-")
    return "-".join(parts[0:5]) if len(parts)==7 else None

def suggest():
    for node in get_sorted_nodes(nodes,lambda node: node["top_mem_p"], lambda pod: pod["top_mem"]-pod["req_mem"]):
        if node["top_mem_p"] > 80:
            for pod in node["pods"]:
                deployment_name = get_deployment_name(pod["pod_name"])
                if pod["top_mem"]>pod["req_mem"] and deployment_name:
                    mem_v = pod["top_mem"]
                    cpu_v = pod["req_cpu"]
                    requests = f"--requests=memory={mem_v}Mi,cpu={cpu_v}m"
                    return " ".join([*kc,"set","resources",requests,"deployment",deployment_name])

suggested = suggest()
if suggested:
    print("suggest:\n"+suggested)
