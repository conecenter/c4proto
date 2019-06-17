
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

#print(md5s([b'ABC',b'DEF']))
#print("=".join([urllib.parse.quote_plus(e) for e in ["/A","/B"]]))
#print(pathlib.Path("req.py").read_text())
#read_bytes() encoding='utf-8'
#sys.argv[1:]
#print(int((time.time()+3600)*1000))

cmd, salt_path, body_path, url, *data = sys.argv
salt = [get_file(salt_path)]
until = [str(int((time.time()+3600)*1000)).encode("utf-8")]
uData = until + [s.encode("utf-8") for s in data]
hash = [md5s(salt + uData)]
header = "=".join([urllib.parse.quote_plus(e) for e in hash + uData])
headers = { "X-r-signed": header }
req =  urllib.request.Request(url, data=get_file(body_path), headers=headers)
resp = urllib.request.urlopen(req)
print(resp)