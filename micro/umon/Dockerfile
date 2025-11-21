FROM ubuntu:24.04
COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /
RUN perl install.pl useradd 1979
RUN perl install.pl apt curl ca-certificates python3-venv tini
USER c4
RUN python3 -m venv /c4/venv && /c4/venv/bin/pip install --no-cache-dir playwright prometheus-client
USER root
RUN /c4/venv/bin/playwright install-deps && rm -rf /var/lib/apt/lists/*
USER c4
RUN /c4/venv/bin/playwright install chromium-headless-shell
ENTRYPOINT ["tini","--","/c4/venv/bin/python","-u","/app/main.py"]
