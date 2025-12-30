FROM ubuntu:24.04
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4
RUN apt update && DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    ca-certificates python3 tini \
 && rm -rf /var/lib/apt/lists/*
ADD --link --chmod=755 https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl /tools/kubectl
USER c4
ENV PATH=${PATH}:/tools
ENTRYPOINT ["tini","--","python3","-u","/app/main.py"]
COPY . /app