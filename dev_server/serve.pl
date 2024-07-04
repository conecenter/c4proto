
use strict;
use JSON::XS;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $exec_at = sub{
    my($dir,$env,@args)=@_;
    chdir $dir or die $dir;
    $ENV{$_} = $$env{$_}, print "$_='$$env{$_}' \\\n" for keys %$env;
    &$exec(@args);
};
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $single = sub{ @_==1 ? $_[0] : die };
#my $group = sub{ my %r; push @{$r{$$_[0]}||=[]}, $$_[1] for @_; (sub{@{$r{$_[0]}||[]}},[sort keys %r]) };
my $distinct = sub{ my(@r,%was); $was{$_}++ or push @r,$_ for @_; @r };

my $zoo_port = 8081;
my $bootstrap_server = "localhost:8093"; #dup
my $http_port = sub{8067+$_[0]*100}; #dup
my $sse_port = sub{8068+$_[0]*100}; #dup
my $get_repo_dir = sub{ $ENV{C4DS_BUILD_DIR} || die "no C4DS_BUILD_DIR" };
my $get_proto_dir = sub{ $ENV{C4DS_PROTO_DIR} || die "no C4DS_PROTO_DIR" };
my $elector_dir = $ENV{C4DS_ELECTOR_DIR} || die "no C4DS_ELECTOR_DIR";
my $home = $ENV{HOME} || die;
my $data_dir = $home;
my $s3conf_dir = "$data_dir/minio-conf";

#my $serve_b loop = sub{
#    &$exec_at(".",{
#        JAVA_TOOL_OPTIONS => '-Xss32m',
#    },"b loop","server");
#};

my $serve_zookeeper = sub{
    &$put_text("$data_dir/zookeeper.properties","dataDir=$data_dir/zookeeper\nclientPort=$zoo_port\n");
    &$exec("zookeeper-server-start.sh","$data_dir/zookeeper.properties");
};

my $serve_broker = sub{
    &$put_text("$data_dir/server.properties", join '', map{"$_\n"}
        "log.dirs=$data_dir/kafka-logs",
        "zookeeper.connect=127.0.0.1:$zoo_port",
        "listeners=PLAINTEXT://$bootstrap_server",
    );
    &$exec("kafka-server-start.sh","$data_dir/server.properties");
};

my $elector_port_base = 6000;
my $elector_proxy_port_base = 6010;
my $elector_replicas = 3;
my $vite_port = 5173;

my $serve_proxy = sub{
    &$put_text("$data_dir/haproxy.cfg", join '', map{"$_\n"}
        "defaults",
        "  timeout connect 5s",
        "  timeout client  900s",
        "  timeout server  900s",
        "frontend fe_http",
        "  mode http",
        "  bind :1080",
        #"  use_backend be_sse if { path_beg /sse }",
        "  use_backend be_src if { path_beg /src/ }",
        "  use_backend be_src if { path_beg /\@ }",
        "  use_backend be_src if { path_beg /node_modules/ }",
        "  default_backend be_http",
        "backend be_src",
        "  mode http",
        "  server se_src 127.0.0.1:$vite_port",
        "backend be_http",
        "  mode http",
        "  default-server check", # w/o it all servers considered ok and req-s gets 503
        "  server be_http_0 127.0.0.1:".&$http_port(0),
        "  server be_http_1 127.0.0.1:".&$http_port(1),
        #"backend be_sse",
        #"  mode http",
        #"  server se_sse 127.0.0.1:$sse_port",
        # this is for HA elector test:
        (map{
            my $from_port = $elector_proxy_port_base + $_;
            my $to_port = $elector_port_base + $_;
            (
                "listen listen_$from_port",
                "  bind :$from_port",
                "  server s_def 127.0.0.1:$to_port",
            )
        } 0..$elector_replicas)

    );
    &$exec("haproxy","-f","$data_dir/haproxy.cfg");
};

my $serve_node = sub{
    my $repo_dir = &$get_repo_dir();
    my $vite_run_dir = "$repo_dir/target/c4/client";
    my $conf_dir = "$vite_run_dir/src/c4f/vite";
    my $conf = JSON::XS->new->decode(syf("cat $repo_dir/c4dep.main.json"));
    my %will = map{ ref && $$_[0] eq "C4CLIENT" ? ("$vite_run_dir/src/$$_[1]","$repo_dir/$$_[2]/src"):() } @$conf;
    #$will{$_} or ^rm $_^ for <$vite_run_dir/src/*>;
    for(sort keys %will){
        sy("mkdir", "-p", $_);
        sy("rsync", "-a", "$will{$_}/", $_);
    }
    sy("cd $vite_run_dir && cp $conf_dir/package.json $conf_dir/vite.config.js . && npm install");
    &$exec_at($vite_run_dir,{},"npm","run","dev","--","--port","$vite_port");
};

my $get_compilable_services = sub{
    my $repo_dir = &$get_repo_dir();
    my $tag_path = "$repo_dir/target/c4/tag";
    my $main = (-e $tag_path) ? syf("cat $tag_path") : $ENV{C4DEV_SERVER_MAIN} || die "no C4DEV_SERVER_MAIN";
    [
        { name=>"gate", dir=>&$get_proto_dir(), main => "def", replicas => [0,1] },
        { name=>"main", dir=>$repo_dir, main => $main, replicas => [0,1] },
    ]
};

my $get_tag_info = sub{
    my($compilable_service)=@_;
    my $dir = $$compilable_service{dir} || die;
    my $arg = $$compilable_service{main} || die;
    my $build_data = JSON::XS->new->decode(syf("cat $dir/target/c4/build.json"));
    ($dir, map{$$build_data{tag_info}{$arg}{$_}||die} qw[name mod main]);
};

my $inbox_topic_prefix = "def0";

my $get_consumer_env = sub{
    my ($nm,$elector_port_base_arg)=@_;
    my $readiness_path = "/tmp/c4is-ready-$$";
    !-e $readiness_path or unlink $readiness_path or die;
    my $elector_servers = join ",", map{
        my $port = $elector_port_base_arg + $_;
        "http://127.0.0.1:$port"
    } 0..$elector_replicas-1;
    (
        C4KAFKA_CONFIG => "C\nbootstrap.servers\n$bootstrap_server",
        C4STATE_TOPIC_PREFIX => $nm,
        C4INBOX_TOPIC_PREFIX => $inbox_topic_prefix,
        C4S3_CONF_DIR => $s3conf_dir,
        C4BROKER_MIN_LO_SIZE => "0",
        C4HTTP_SERVER => "http://127.0.0.1:1080",
        C4AUTH_KEY_FILE => "$data_dir/simple.auth",
        C4ELECTOR_SERVERS => $elector_servers,
        C4READINESS_PATH => $readiness_path,
    )
};

my $get_gate_env = sub{
    my($replica)=@_;
    (
        C4STATE_REFRESH_SECONDS=>100,
        C4ROOMS_CONF=>"/tmp/rooms.conf",
        C4HTTP_PORT => &$http_port($replica),
        C4SSE_PORT => &$sse_port($replica),
        C4POD_IP => "127.0.0.1",
        C4KEEP_SNAPSHOTS => "default",
    )
};

my $exec_server = sub{
    my($add_env,$service_name,$replica)=@_;
    my $compilable_services = &$get_compilable_services();
    my $compilable_service =
        &$single(grep{$$_{name} eq $service_name} @$compilable_services);
    my ($dir,$nm,$mod,$cl) = &$get_tag_info($compilable_service);
    my $proto_dir = &$get_proto_dir();
    my $paths = JSON::XS->new->decode(syf("python3 $proto_dir/build_env.py $dir $mod"));
    my $env = {
        &$get_consumer_env($nm, $replica>0?$elector_proxy_port_base:$elector_port_base),
        C4APP_CLASS => "ee.cone.c4actor.ParentElectorClientApp",
        C4APP_CLASS_INNER => $cl,
        %$paths,
        %$add_env,
    };
    &$exec_at($dir,$env,"java","ee.cone.c4actor.ServerMain");
};

my $serve_gate = sub{
    my($replica)=@_;
    &$exec_server({&$get_gate_env($replica)}, "gate", $replica);
};

my $serve_main = sub{
    my($replica)=@_;
    &$exec_server({}, "main", $replica);
};

my $serve_build = sub{
    my $proto_dir = &$get_proto_dir();
    my $compilable_services = &$get_compilable_services();
    for my $dir(&$distinct(map{$$_{dir}} @$compilable_services)){
        sy("python3", "$proto_dir/build.py", $dir);
    }
    for my $compilable_service(@$compilable_services){
        my ($dir,$nm,$mod,$cl) = &$get_tag_info($compilable_service);
        sy("cd $dir/target/c4/mod.$mod.d && sbt -J-Xmx16G c4build");
        sy("supervisorctl restart $$compilable_service{name}$_")
            for @{$$compilable_service{replicas} || die};
    }
    sleep 1 while 1; #todo: check some ver here
};

my $serve_minio = sub{
    &$exec_at(".",{
        MINIO_ACCESS_KEY => syf("cat $s3conf_dir/key"),
        MINIO_SECRET_KEY => syf("cat $s3conf_dir/secret"),
    },"minio","server","$data_dir/minio-data");
};

my $serve_mcl = sub{
    sy("sh $s3conf_dir/setup");
    sleep 1 while 1;
};

my $serve_elector = sub{
    my($replica)=@_;
    &$exec_at($elector_dir,{
        C4HTTP_PORT => $elector_port_base+$replica
    }, "node","elector.js");
};

my $replicas = sub{
    my($key,$serve,$replicas)=@_;
    map{my $r=$_;("$key$r"=>sub{&$serve($r,@_)})} 0..$replicas-1
};

my $exec_demo_server = sub{
    my($add_env,$nm,$dir)=@_;
    my $env = { &$get_consumer_env($nm, $elector_port_base), %$add_env };
    &$exec_at($dir,$env,"perl","run.pl","main");
};

my $serve_demo_jasper = sub{
    my $dir = "/tools/c4jasper_server";
    -e $dir and &$exec_at($dir,{},"perl","run.pl","main");
    sleep 1 while 1;
};

my $serve_demo_main = sub{
    my $env = { C4JR => "http://127.0.0.1:1080/" };
    &$exec_demo_server($env, main => "/tools/c4main");
};
my $serve_demo_gate = sub{
    sleep 1 while so("sh $s3conf_dir/setup");
    my $dir = "local/$inbox_topic_prefix.snapshots/";
    my $snapshot_path = $ENV{C4DS_SNAPSHOT_PATH};
    $snapshot_path and !so("mcl mb $dir") and sy("mcl cp $snapshot_path $dir");
    &$exec_demo_server({ &$get_gate_env(0) }, gate => "/tools/c4gate")
};

my $common_service_map = {
    zookeeper => $serve_zookeeper,
    broker => $serve_broker,
    minio => $serve_minio,
    &$replicas(elector => $serve_elector, $elector_replicas),
};
my $dev_service_map = {
    build => $serve_build,
    #b loop => $serve_b loop,
    proxy => $serve_proxy,
    node  => $serve_node,
    &$replicas(gate => $serve_gate, 2),
    &$replicas(main => $serve_main, 2),
    mcl => $serve_mcl,
};
my $demo_service_map = {
    demo_gate => $serve_demo_gate,
    demo_main => $serve_demo_main,
    demo_jasper => $serve_demo_jasper,
};

my $init_s3 = sub{
    my $address = "http://127.0.0.1:9000";
    my($key,$secret) = map{ syf("uuidgen")=~/(\S+)/ ? "$1" : die } 0,1;
    mkdir $s3conf_dir;
    &$put_text("$s3conf_dir/address",$address);
    &$put_text("$s3conf_dir/key",$key);
    &$put_text("$s3conf_dir/secret",$secret);
    &$put_text("$s3conf_dir/setup","mcl alias set local $address $key $secret || exit 1");
};

my $init = sub{
    my($service_map) = @_;
    &$init_s3();
    my $ca_auth = "$data_dir/simple.auth";
    &$put_text($ca_auth, syf("uuidgen")=~/(\S+)/ ? $1 : die) if !-e $ca_auth;
    my @program_lines = map{(
        "[program:$$_[0]]",
        "command=perl $0 $$_[1]",
        "autorestart=true",
        "stopasgroup=true",
        "killasgroup=true",
        $ENV{C4MERGE_LOGS} ? (
            "stdout_syslog=true",
            "stderr_syslog=true",
            # "stderr_logfile=/dev/stderr",
            # "stderr_logfile_maxbytes=0",
            # "stdout_logfile=/dev/stdout",
            # "stdout_logfile_maxbytes=0",
        ):()
    )} map{
        [$_,$_]
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

my $init_dev = sub{
    my $proto_dir = &$get_proto_dir();
    my $repo_dir = &$get_repo_dir();
    sy("perl $proto_dir/sync.pl clean_local $repo_dir");
    &$init({%$common_service_map,%$dev_service_map})
};
my $init_demo = sub{ &$init({%$common_service_map,%$demo_service_map}) };

my $cmd_map = {
    %$common_service_map, %$dev_service_map, %$demo_service_map,
    init_dev  => $init_dev,
    init_demo => $init_demo,
};

my ($cmd,@args) = @ARGV;
$$cmd_map{$cmd}->(@args);
