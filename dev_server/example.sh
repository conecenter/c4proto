export C4DS_BUILD_DIR=/c4repo/c4proto
export C4REPO_MAIN_CONF=/c4repo/c4proto/c4dep.main.replink
export C4DEV_SERVER_MAIN=base_examples.ee.cone.c4ui.TestTodoApp
docker build -t c4devel . && docker-compose up -d
