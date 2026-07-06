# syntax=docker/dockerfile:1
FROM ubuntu:26.04 AS u26
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4

FROM u26 AS build-python
ADD --link --unpack "https://github.com/astral-sh/python-build-standalone/releases/download/20260623/cpython-3.14.6+20260623-x86_64_v2-unknown-linux-gnu-freethreaded-install_only_stripped.tar.gz" /tools

FROM u26 AS build-node
ADD --link --unpack https://nodejs.org/dist/v22.15.1/node-v22.15.1-linux-x64.tar.xz /tools
RUN mv /tools/node-v22.15.1-linux-x64 /tools/node

FROM u26 AS build-proxy
ADD --link --unpack https://github.com/oauth2-proxy/oauth2-proxy/releases/download/v7.9.0/oauth2-proxy-v7.9.0.linux-amd64.tar.gz /tools
RUN mv /tools/oauth2-proxy-v7.9.0.linux-amd64 /tools/proxy

FROM u26 AS build-async
ADD --link --unpack "https://github.com/async-profiler/async-profiler/releases/download/v4.1/async-profiler-4.1-linux-x64.tar.gz" /tools
RUN mv /tools/async-profiler-4.1-linux-x64 /tools/async

FROM u26 AS build-app
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates
COPY --link --from=build-python /tools/python /tools/python
COPY --link --from=build-node /tools/node /tools/node
USER c4
ENV PATH=${PATH}:/tools/python/bin:/tools/node/bin
RUN mkdir /c4/c4client && cd /c4/c4client && npm install esbuild@^0.25.4 react@^19.1.0 react-dom@^19.1.0 @tailwindcss/cli@^4.1.7 @tanstack/react-query@5.101.2
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir uvicorn[standard]==0.47.0 starlette==1.3.1 boto3==1.38.34
COPY --chown=c4:c4 build.py *.js *.jsx *.svg /c4/c4client/
RUN python3 -u /c4/c4client/build.py

FROM u26
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates tini micro && rm -rf /var/lib/apt/lists/*
COPY --link --from=build-python /tools/python /tools/python
COPY --link --from=build-proxy /tools/proxy /tools/proxy
COPY --link --from=build-async /tools/async /tools/async
ADD --link --chmod=+x "https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl" /tools/kubectl
USER c4
COPY --chown=c4:c4 --from=build-app /c4/venv /c4/venv
ENV PATH=${PATH}:/tools:/tools/proxy:/tools/async/bin:/c4/venv/bin
ENTRYPOINT ["tini","--","/c4/venv/bin/python","-u","-c","from app import main;main()"]
#ENTRYPOINT ["tini","--","perl","-e","exec 'sleep','infinity';die"]
COPY --chown=c4:c4 --from=build-app /c4/c4client/app.html /c4/c4client/app.ver /app/
COPY --chown=c4:c4 *.py /app/
