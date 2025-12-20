FROM ubuntu:22.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates xz-utils tini
# xz-utils for node
RUN perl install.pl curl https://nodejs.org/dist/v20.5.0/node-v20.5.0-linux-x64.tar.xz
USER c4
ENTRYPOINT ["tini","--","/tools/node/bin/node","/app/elector.js"]
COPY . /app