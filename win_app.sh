set -x

install() {
  sudo apt-get update
  sudo apt-get install gcc -y
  sudo apt-get make -y
  cp -r /mnt/c/Users/$1/.ssh ~/.ssh
  chmod 600 ~/.ssh/id_rsa
  chmod 600 ~/.ssh/id_rsa.pub
  curl -L http://xrl.us/installperlnix | bash
}

build_all() {
  export C4BUILD_PORT=22
  export C4BUILD_ADDR="127.0.21.1"
  ./app.pl build_all
}

build_diff() {
  export C4BUILD_PORT=22
  export C4BUILD_ADDR="127.0.21.1"
  C4BUILD_CLEAN=0 ./app.pl build_all
}

while test $# -gt 0; do
  case "$1" in
  --*)
    echo "bad option $1"
    ;;
  install)
    install $2
    ;;
  build_all)
    build_all
    ;;
  build_diff)
    build_diff
    ;;
  *)
    echo "unsupported argument $1"
    ;;
  esac
  shift
done
