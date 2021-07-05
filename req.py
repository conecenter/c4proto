
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

def signed_req(salt,responseKey,args,opt):
    until = [str(int((time.time()+3600)*1000)).encode("utf-8")]
    uData = until + [s.encode("utf-8") for s in args]
    hash = [md5s([salt] + uData)]
    header = "=".join([urllib.parse.quote_plus(e) for e in hash + uData])
    headers = { "x-r-signed": header, "x-r-response-key": responseKey }
    postReq =  urllib.request.Request(headers=headers, **opt) #method="POST",
    postResp = urllib.request.urlopen(postReq,timeout=60)
    if postResp.status!=200:
        raise Exception("req sending failed")
    resp = None
    while resp is None:
        time.sleep(1)
        print(".")
        req = urllib.request.Request(url = host+"/response/"+responseKey)
        try:
            resp = urllib.request.urlopen(req)
        except:
            pass
    err = resp.getheader("x-r-error-message")
    if not (err is None or err == ""):
        raise Exception("post handling failed: "+err)

cmd, salt_path, body_path, host, url, *args = sys.argv
salt = get_file(salt_path)
responseKey = str(uuid.uuid4())
data = get_file(body_path)
try:
    signed_req(salt,responseKey,args,{ "url": host+url, "data": data })
except Exception as e:
    raise Exception(f"Signed request error ({host+url})") from e
