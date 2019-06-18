
import urllib
import hashlib
import base64
import pathlib
import time
import sys
import urllib.request

def md5s(data):
    digest = hashlib.md5()
    print(data)
    for bytes in data:
        l = len(bytes).to_bytes(4,byteorder='big')
        digest.update(l)
        digest.update(bytes)
    return base64.urlsafe_b64encode(digest.digest())

def get_file(fn):
    return pathlib.Path(fn).read_bytes()

def signed_req(salt,args,opt):
    until = [str(int((time.time()+3600)*1000)).encode("utf-8")]
    uData = until + [s.encode("utf-8") for s in args]
    hash = [md5s([salt] + uData)]
    header = "=".join([urllib.parse.quote_plus(e) for e in hash + uData])
    headers = { "X-r-signed": header }
    req =  urllib.request.Request(headers=headers, **opt)
    return urllib.request.urlopen(req)


cmd, salt_path, body_path, url, *args = sys.argv
salt = get_file(salt_path)
{
    url: url,
    data: get_file(body_path)
}

print(resp)