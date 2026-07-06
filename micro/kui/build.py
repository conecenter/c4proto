
from base64 import b64encode
from hashlib import sha256
from json import dumps
from pathlib import Path
from subprocess import check_call, Popen

def build_client():
    path_parent = Path(__file__).parent
    (path_parent/"input.css").write_bytes(b'@import "tailwindcss" source(none);\n@source "app.jsx";')
    css_proc = Popen(("env","-C",str(path_parent),"npx","tailwindcss","-i","input.css","-o","out.css"))
    check_call(("env","-C",str(path_parent),"node_modules/.bin/esbuild","app.jsx","--bundle","--outfile=out.js"))
    if css_proc.wait() != 0: raise Exception("css")
    js_data = (path_parent/"out.js").read_bytes()
    css_data = (path_parent/"out.css").read_bytes()
    favicon = (path_parent/"favicon.svg").read_bytes()
    ver = sha256(js_data).hexdigest()
    content = (
        '<!DOCTYPE html><html lang="en">' +
        '<head>' +
        f'<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,{b64encode(favicon).decode()}" />' +
        f'<meta charset="UTF-8"><title>c4</title><style>{css_data.decode()}</style>' +
        '</head>' +
        f'<body><script type="module">const c4_app_version={dumps(ver)};\n{js_data.decode()}</script></body>' +
        '</html>'
    )
    (path_parent/"app.html").write_bytes(content.encode())
    (path_parent/"app.ver").write_bytes(ver.encode())

build_client()