FROM ubuntu:26.04
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4
RUN apt update && DEBIAN_FRONTEND=noninteractive apt install -y microsocks lsof
USER c4
ENTRYPOINT ["perl","-e","exec 'microsocks','-i','127.0.0.1';die"]
