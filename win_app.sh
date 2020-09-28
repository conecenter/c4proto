install() {
  sudo apt-get update
  sudo apt-get install gcc -y
  sudo apt-get make -y
  cp -r /mnt/c/Users/ilya2/.ssh ~/.ssh
  chmod 600 ~/.ssh/id_rsa
  curl -L http://xrl.us/installperlnix | bash
}

build_all() {
  export C4BUILD_PORT=22
  export C4BUILD_ADDR="127.0.21.1"
  export C4CONN_CMD="ssh.exe"
  ./app.pl build_all
}

while test $# -gt 0; do
  case "$1" in
  --*)
    echo "bad option $1"
    ;;
  install)
    install
    ;;
  build_all)
    build_all
    ;;
  *)
    echo "unsupported argument $1"
    ;;
  esac
  shift
done
