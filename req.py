
import urllib
import hashlib
import base64
import pathlib
import time
import sys
import urllib.request
import uuid

def md5s(data):
    digest = hashlib.md5()
    #print(data)
    for bytes in data:
        l = len(bytes).to_bytes(4,byteorder='big')
        digest.update(l)
        digest.update(bytes)
    return base64.urlsafe_b64encode(digest.digest())

def get_file(fn):
    return pathlib.Path(fn).read_bytes()

def retry_loop_url(url):
    while True:
        try:
            return urllib.request.urlopen(urllib.request.Request(url=url))
        except:
            time.sleep(1)
            print(".")

def signed_req(salt,responseKey,args,opt):
    until = [str(int((time.time()+3600)*1000)).encode("utf-8")]
    uData = until + [s.encode("utf-8") for s in args]
    hash = [md5s([salt] + uData)]
    header = "=".join([urllib.parse.quote_plus(e) for e in hash + uData])
    headers = { "x-r-signed": header, "x-r-response-key": responseKey }
    postReq =  urllib.request.Request(headers=headers, **opt) #method="POST",
    print(f"req ({opt['url']}) urlopen starting ({time.time()})")
    postResp = urllib.request.urlopen(postReq,timeout=300)
    print(f"req ({opt['url']}) urlopen ok")
    if postResp.status!=200:
        raise Exception("req sending failed")
    resp = retry_loop_url(host+"/response/"+responseKey)
    err = resp.getheader("x-r-error-message")
    if not (err is None or err == ""):
        raise Exception("post handling failed: "+err)
    print(f"req ({opt['url']}) ok")

cmd, salt_path, body_path, host, url, *args = sys.argv
salt = get_file(salt_path)
responseKey = str(uuid.uuid4())
data = get_file(body_path)
try:
    signed_req(salt,responseKey,args,{ "url": host+url, "data": data })
except Exception as e:
    raise Exception(f"Signed request error ({host+url}) at ({time.time()})") from e
retry_loop_url(host+"/seen/response/"+responseKey)
print(f"req to {host+url} was seen at ({time.time()})")
