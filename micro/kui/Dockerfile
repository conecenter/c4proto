FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates xz-utils git python3-venv tini lsof mc
RUN perl install.pl curl https://github.com/oauth2-proxy/oauth2-proxy/releases/download/v7.9.0/oauth2-proxy-v7.9.0.linux-amd64.tar.gz
RUN perl install.pl curl https://nodejs.org/dist/v22.15.1/node-v22.15.1-linux-x64.tar.xz
RUN perl install.pl curl https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl && chmod +x /tools/kubectl
USER c4
ENV PATH=${PATH}:/tools:/tools/oauth:/tools/node/bin
RUN mkdir /c4/c4client && cd /c4/c4client && npm install esbuild@^0.25.4 react@^19.1.0 react-dom@^19.1.0 @tailwindcss/cli@^4.1.7
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir websockets==15.0.1
ENTRYPOINT ["tini","--","/c4/venv/bin/python","-u","-c","from app import main;main()"]
