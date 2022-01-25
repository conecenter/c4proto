docker stop c4devel
docker rm c4devel
export C4DS_BUILD_DIR=/c4repo/c4proto
export C4REPO_MAIN_CONF=/c4repo/c4proto/c4dep.main.replink
export C4DEV_SERVER_MAIN=base_examples.ee.cone.c4ui.TestTodoApp
docker build -t c4devel --build-arg C4UID=$(id -u) . || exit 1
export C4DEV_SERVER_IP="${C4DEV_SERVER_IP:-127.0.0.10}"
docker run -d --rm --name c4devel \
  -v $PWD/..:/c4repo/c4proto \
  -v /dev/log:/dev/log \
  -p $C4DEV_SERVER_IP:3000:3000 \
  -p $C4DEV_SERVER_IP:80:1080 \
  -e C4DS_PROTO_DIR=/c4repo/c4proto \
  -e C4DS_ELECTOR_DIR=/c4repo/c4proto \
  -e C4DS_BUILD_DIR=${C4DS_BUILD_DIR} \
  -e C4REPO_MAIN_CONF=${C4REPO_MAIN_CONF} \
  -e C4DEV_SERVER_MAIN=${C4DEV_SERVER_MAIN} \
  -e C4MERGE_LOGS=1 \
  c4devel || exit 1