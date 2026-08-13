# syntax=docker/dockerfile:1
FROM ubuntu:26.04 AS u26
RUN useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4

FROM u26 AS build-c4dig
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip
ADD --link --unpack "https://github.com/async-profiler/async-profiler/releases/download/v4.1/async-profiler-4.1-linux-x64.tar.gz" /c4dig
ADD --link --unpack "https://github.com/alibaba/arthas/releases/download/arthas-all-4.1.1/arthas-bin.zip" /arthas-bin.zip
RUN unzip /arthas-bin.zip -d /c4dig/arthas \
 && mv /c4dig/async-profiler-4.1-linux-x64 /c4dig/async \
 && mkdir /c4dig/async_out \
 && chown -R c4:c4 /c4dig \
 && tar -czf /c4/c4dig.tar.gz c4dig

FROM u26 AS build-jdk
ADD --link --unpack "https://download.bell-sw.com/java/25.0.2+12/bellsoft-jdk25.0.2+12-linux-amd64.tar.gz" /tools
RUN mv /tools/jdk-25.0.2 /tools/jdk

FROM u26 AS build-app
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates
ADD --link --unpack "https://github.com/sbt/sbt/releases/download/v1.11.7/sbt-1.11.7.tgz" /tools
COPY --link --from=build-jdk /tools/jdk /tools/jdk
USER c4
ENV PATH=${PATH}:/tools/jdk/bin:/tools/sbt/bin
RUN mkdir -p /c4/app/project && cd /c4/app \
 && echo 'addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")' > project/plugins.sbt \
 && echo 'scalaVersion := "3.7.2"' > build.sbt \
 && echo 'libraryDependencies += "com.lihaoyi" %% "ujson" % "4.4.1"' >> build.sbt \
 && echo 'assembly / assemblyJarName := "c4dig.jar"' >> build.sbt \
 && sbt update
COPY app.scala /c4/app/
RUN env -C /c4/app sbt assembly && cp /c4/app/target/scala-3.7.2/c4dig.jar /c4/c4dig.jar

FROM u26
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates tini && rm -rf /var/lib/apt/lists/*
ADD --link --chmod=+x "https://dl.k8s.io/release/v1.33.1/bin/linux/amd64/kubectl" /tools/kubectl
COPY --link --from=build-jdk /tools/jdk /tools/jdk
COPY --link --from=build-c4dig /c4/c4dig.tar.gz /c4/c4dig.tar.gz
COPY --link --from=build-app /c4/c4dig.jar /c4/c4dig.jar
COPY agent.py /c4/agent.py
USER c4
ENV PATH=${PATH}:/tools:/tools/jdk/bin
ENV C4UP_PATH=/c4
ENTRYPOINT ["tini","--","java","-XX:+ExitOnOutOfMemoryError","-jar","/c4/c4dig.jar"]