FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3-venv tini lsof
USER c4
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir av pillow websockets
ENTRYPOINT ["tini","--","/c4/venv/bin/python","-u","/app/ws4cam.py"]
COPY . /app