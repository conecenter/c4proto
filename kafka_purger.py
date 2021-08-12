
import subprocess
import re
import os

kubectl_res = subprocess.run(["kubectl","--context","dev","get","deployments"],check=True,capture_output=True,text=True).stdout
deployed_prefixes = re.findall(r'\n(qp-\S+)-gate\s',kubectl_res)
deployed_topics = set(f"{pre}.inbox" for pre in deployed_prefixes)
proto_dir = os.environ["C4CI_PROTO_DIR"]
coursier_res = subprocess.run(["coursier","fetch","--classpath","org.apache.kafka:kafka-clients:2.8.0"],check=True,capture_output=True,text=True).stdout
classpath = re.search(r'\S+',coursier_res).group(0)
kafka_cmd = ["java","--source","15",proto_dir+"/kafka_info.java"]
kafka_env = {**os.environ,"CLASSPATH":classpath}
topics_res = subprocess.run(kafka_cmd+["topics"],env=kafka_env,check=True,text=True,capture_output=True).stdout
kafka_topics = re.findall(r'\ntopic\s(qp-\S+\.inbox)\s',"\n"+topics_res)
rm_topics = [topic for topic in kafka_topics if topic not in deployed_topics]
rm_str = "".join(f"{topic}\n" for topic in rm_topics)
print(f"{len(kafka_topics)} topics found, {len(rm_topics)} to remove")
subprocess.run(kafka_cmd+["topics_rm"],env=kafka_env,check=True,text=True,input=rm_str)
