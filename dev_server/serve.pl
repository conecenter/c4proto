
use strict;
use JSON::XS;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $exec_at = sub{
    my($dir,$env,@args)=@_;
    chdir $dir or die $dir;
    $ENV{$_} = $$env{$_} for keys %$env;
    &$exec(@args);
};
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $relink = sub{
    my($f,$l)=@_;
    if($f ne readlink $l){ unlink $l; symlink $f, $l or die $!, $f, $l }
};
my $group = sub{ my %r; push @{$r{$$_[0]}||=[]}, $$_[1] for @_; (sub{@{$r{$_[0]}||[]}},[sort keys %r]) };

my $zoo_port = 8081;
my $ssl_bootstrap_server = "localhost:8093"; #dup
my $repo_dir = $ENV{C4CI_BUILD_DIR} || die "no C4CI_BUILD_DIR";
my $proto_dir = $ENV{C4CI_PROTO_DIR} || die "no C4CI_PROTO_DIR";
my $home = $ENV{HOME} || die;
my $data_dir = $home;

my $serve_bloop = sub{
    -e "$home/.bloop/bloop" or sy("curl -L https://github.com/scalacenter/bloop/releases/download/v1.3.4/install.py | python");
    &$exec("bloop","server");
};

my $serve_zookeeper = sub{
    &$put_text("$data_dir/zookeeper.properties","dataDir=$data_dir/zookeeper\nclientPort=$zoo_port\n");
    &$exec("zookeeper-server-start.sh","$data_dir/zookeeper.properties");
};

my $serve_broker = sub{
    &$put_text("$data_dir/server.properties", join '', map{"$_\n"}
        "log.dirs=$data_dir/kafka-logs",
        "zookeeper.connect=127.0.0.1:$zoo_port",
        "message.max.bytes=250000000", #seems to be compressed
        "listeners=SSL://$ssl_bootstrap_server",
        "inter.broker.listener.name=SSL",
        "socket.request.max.bytes=250000000",
    );
    sy("perl $proto_dir/prod.pl need_certs $data_dir/ca cu.broker $data_dir $data_dir");
    sy("perl $proto_dir/prod.pl need_certs $data_dir/ca cu.def $data_dir");
    sy("cat $data_dir/cu.broker.properties >> $data_dir/server.properties");
    &$exec("kafka-server-start.sh","$data_dir/server.properties");
};

my $serve_proxy = sub{
    &$put_text("$data_dir/haproxy.cfg", join '', map{"$_\n"}
        "defaults",
        "  timeout connect 5s",
        "  timeout client  900s",
        "  timeout server  900s",
        "frontend fe_http",
        "  mode http",
        "  bind :1080",
        "  use_backend be_sse if { path_beg /sse }",
        "  use_backend be_src if { path_beg /src/ }",
        "  use_backend be_src if { path_beg /\@ }",
        "  use_backend be_src if { path_beg /node_modules/ }",
        "  default_backend be_http",
        "backend be_src",
        "  mode http",
        "  server se_src 127.0.0.1:3000",
        "backend be_http",
        "  mode http",
        "  server be_http 127.0.0.1:8067",
        "backend be_sse",
        "  mode http",
        "  server se_sse 127.0.0.1:8068",
    );
    &$exec("haproxy","-f","$data_dir/haproxy.cfg");
};

my $serve_node = sub{
    my $vite_run_dir = "$data_dir/vite";
    sy("mkdir -p $vite_run_dir/src");
    for(@{JSON::XS->new->decode(syf("cat $repo_dir/c4dep.main.json"))}){
        ref $_ or next;
        my($op,$from,$to)=@$_;
        $op eq 'C4CLIENT' or next;
        $from && $to || die;
        &$relink("$repo_dir/$to/src","$vite_run_dir/src/$from");
    }
    my $conf_dir = "$vite_run_dir/src/c4f/vite";
    sy("cd $vite_run_dir && cp $conf_dir/package.json $conf_dir/vite.config.js . && npm install");
    &$exec_at($vite_run_dir,{},"npm","run","dev");
};

my $serve_gate = sub{
    &$exec_at($proto_dir, {
        C4DATA_DIR=>$data_dir,
        C4STATE_REFRESH_SECONDS=>100,
        # JAVA_TOOL_OPTIONS=>"-XX:ActiveProcessorCount=36", #todo: fix work without many cores
    }, "$proto_dir/do.pl run_server def");
};

my $serve_main = sub{
    my $main = $ENV{C4DEV_SERVER_MAIN} || die "no C4DEV_SERVER_MAIN";
    &$exec_at($repo_dir, {C4DATA_DIR=>$data_dir}, "$proto_dir/do.pl run_server $main");
};

my $serve_build = sub{
    my($dir,$services_str)=@_;
    $dir && $services_str || die;
    sy("cd $dir && perl $proto_dir/build.pl");
    sy("supervisorctl restart $_") for split ",", $services_str;
    sleep 1 while 1; #todo: check some ver here
};

my $service_map = {
    build => $serve_build,
    bloop => $serve_bloop,
    zookeeper => $serve_zookeeper,
    broker => $serve_broker,
    proxy => $serve_proxy,
    node => $serve_node,
    gate => $serve_gate,
    main => $serve_main,
};

my $init = sub{
    my ($services_by_build_dir,$build_dirs) = &$group(
        [$repo_dir=>"main"],
        [$proto_dir=>"gate"],
    );
    my @builder_service_lines = map{
        my $dir = $_;
        my @services = &$services_by_build_dir($dir);
        ["build_$services[0]", "build $dir ".join(",",@services)]
    } @$build_dirs;
    my @program_lines = map{(
        "[program:$$_[0]]",
        "command=perl $0 $$_[1]",
        "autorestart=true",
#        "stderr_logfile=/dev/stderr",
#        "stderr_logfile_maxbytes=0",
#        "stdout_logfile=/dev/stdout",
#        "stdout_logfile_maxbytes=0",
    )} map{
        $_ eq 'build' ? @builder_service_lines : [$_,$_]
    } sort keys %$service_map;
    my $sock = "/c4/supervisor.sock";
    &$put_text("$data_dir/supervisord.conf", join '', map{"$_\n"}
        "[supervisord]",
        "nodaemon=true",
        "logfile=/dev/null",
        "logfile_maxbytes=0",
        "[unix_http_server]",
        "file=$sock",
        "[supervisorctl]",
        "serverurl=unix://$sock",
        "[rpcinterface:supervisor]",
        "supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface",
        @program_lines
    );
    &$exec("supervisord","-c","$data_dir/supervisord.conf");
};

my $cmd_map = {
    init => $init,
};

my ($cmd,@args) = @ARGV;
($$service_map{$cmd}||$$cmd_map{$cmd})->(@args);
