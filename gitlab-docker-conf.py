
import os
import json

path = os.environ["C4CI_DOCKER_CONFIG"]
registry = os.environ["CI_REGISTRY"]
user = os.environ["CI_REGISTRY_USER"]
password = os.environ["CI_REGISTRY_PASSWORD"]
data = {"auths":{registry:{"username":user, "password":password}}}
with open(path,"w") as f: json.dump(data, f)