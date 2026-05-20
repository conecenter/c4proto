FROM ubuntu:26.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3-venv tini
USER c4
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir fastapi==0.136.1 'uvicorn[standard]==0.47.0' boto3==1.43.11
ENTRYPOINT ["tini","--","/c4/venv/bin/python","-u","/app/server.py"]
COPY . /app
