
def group_map(l,f):
    res = {}
    for it in l:
        k,v = f(it)
        if k not in res: res[k] = []
        res[k].append(v)
    return res

def one(it): return it

def parse_table(data):
    return [line.split() for line in data.split("\n") if len(line) > 0]

def read_json(path):
    with open(path,'r') as f:
        return json.load(f)

# suggest: read_json, subprocess.run
#
# parse_table