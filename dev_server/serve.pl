
use strict;
use JSON::XS;

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
my $ssl_bootstrap_server = "localhost:8093"; #dup
my $http_port = 8067; #dup
my $sse_port = 8068; #dup
my $http_server = "localhost:$http_port"; #dup
my $repo_dir = $ENV{C4DS_BUILD_DIR} || die "no C4DS_BUILD_DIR";
my $proto_dir = $ENV{C4DS_PROTO_DIR} || die "no C4DS_PROTO_DIR";
my $home = $ENV{HOME} || die;
my $data_dir = $home;
my $s3conf_dir = "$data_dir/minio-conf";

my $serve_bloop = sub{
    #-e "$home/.bloop/bloop" or sy("curl -L https://github.com/scalacenter/bloop/releases/download/v1.3.4/install.py | python");
    &$exec("bloop","server");
};

my $serve_zookeeper = sub{
    &$put_text("$data_dir/zookeeper.properties","dataDir=$data_dir/zookeeper\nclientPort=$zoo_port\n");
    &$exec("zookeeper-server-start.sh","$data_dir/zookeeper.properties");
};

my $need_certs = sub{
    my($dir,$fn_pre,$dir_pre,$cf_pre) = @_;
    my $ca_auth = "$dir/simple.auth";
    my $days = "16384";
    my $ca_key = "$dir/ca-key";
    my $ca_cert = "$dir/ca-cert";
    my $ca_ts = "$dir/truststore.jks";
    my $was_no_ca = !-e $ca_ts;
    if($was_no_ca){
        sy("mkdir -p $dir");
        &$put_text($ca_auth, syf("uuidgen")=~/(\S+)/ ? $1 : die) if !-e $ca_auth;
        sy("openssl req -new -x509 -keyout $ca_key -out $ca_cert -days $days -subj '/CN=CARoot' -nodes");
        sy("keytool -keystore $ca_ts -storepass:file $ca_auth -alias CARoot -import -noprompt -file $ca_cert");
    }
    #
    $dir_pre||die;
    my $auth = "$dir_pre/simple.auth";
    my $pre = "$dir_pre/$fn_pre";
    my $ts = "$pre.truststore.jks";
    my $ks = "$pre.keystore.jks";
    my $csr = "$pre.unsigned";
    my $signed = "$pre.signed";
    my $keytool = "keytool -keystore $ks -storepass:file $auth -alias localhost -noprompt";
    if(!-e $ts){
        sy("cp $ca_auth $auth");
        sy("$keytool -genkey -keyalg RSA -dname 'cn=localhost' -keypass:file $auth -validity $days");
        sy("$keytool -certreq -file $csr");
        sy("openssl x509 -req -CA $ca_cert -CAkey $ca_key -in $csr -out $signed -days $days -CAcreateserial");
        sy("keytool -keystore $ks -storepass:file $auth -alias CARoot -noprompt -import -file $ca_cert -trustcacerts");
        sy("$keytool -import -file $signed -trustcacerts");
        sy("cp $ca_ts $ts");
    }
    #
    if($cf_pre){
        my $auth_data = `cat $auth`=~/^(\S+)\s*$/ ? $1 : die;
        &$put_text("$pre.properties",join '', map{"$_\n"}
            "ssl.keystore.location=$cf_pre/$fn_pre.keystore.jks",
            "ssl.keystore.password=$auth_data",
            "ssl.key.password=$auth_data",
            "ssl.truststore.location=$cf_pre/$fn_pre.truststore.jks",
            "ssl.truststore.password=$auth_data",
            "ssl.endpoint.identification.algorithm=",
            "", #broker
            "ssl.client.auth=required",
            #"security.inter.broker.protocol=SSL",
        );
    }
    $was_no_ca;
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
    sy("cat $data_dir/cu.broker.properties >> $data_dir/server.properties");
    &$exec("kafka-server-start.sh","$data_dir/server.properties");
};

my $elector_port_base = 6000;
my $elector_proxy_port_base = 6010;
my $elector_replicas = 3;

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
        "  server be_http $http_server",
        "backend be_sse",
        "  mode http",
        "  server se_sse 127.0.0.1:$sse_port",
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
    my $vite_run_dir = "$repo_dir/.bloop/c4/client";
    my $conf_dir = "$vite_run_dir/src/c4f/vite";
    sy("cd $vite_run_dir && cp $conf_dir/package.json $conf_dir/vite.config.js . && npm install");
    &$exec_at($vite_run_dir,{},"npm","run","dev");
};

my $compilable_services = [
    { name=>"gate", dir=>$proto_dir,
        main => "def",
        replicas => [''],
    },
    { name=>"main", dir=>$repo_dir,
        main => ($ENV{C4DEV_SERVER_MAIN} || die "no C4DEV_SERVER_MAIN"),
        replicas => [0,1],
    },
];

my $get_tag_info = sub{
    my($compilable_service)=@_;
    my $dir = $$compilable_service{dir} || die;
    my $arg = $$compilable_service{main} || die;
    my $argv = $arg=~/\./ ? $arg : syf("cat $dir/.bloop/c4/tag.$arg.to");
    $argv=~/^(\w+)\.(.+)\.(\w+)$/ ? ($dir,"$1","$1.$2","$2.$3") : die;
};

my $exec_server = sub{
    my($add_env,$service_name,$replica)=@_;
    my $compilable_service =
        &$single(grep{$$_{name} eq $service_name} @$compilable_services);
    my ($dir,$nm,$mod,$cl) = &$get_tag_info($compilable_service);
    my $paths = JSON::XS->new->decode(syf("cat $dir/.bloop/c4/mod.$mod.classpath.json"));
    my $elector_servers = join ",", map{
        my $port = ($replica>0?$elector_proxy_port_base:$elector_port_base) + $_;
        "http://127.0.0.1:$port"
    } 0..$elector_replicas-1;

    my $env = {
        C4BOOTSTRAP_SERVERS => $ssl_bootstrap_server,
        C4INBOX_TOPIC_PREFIX => "def0",
        C4MAX_REQUEST_SIZE => 250000000,
        C4HTTP_SERVER => "http://$http_server",
        C4AUTH_KEY_FILE => "$data_dir/simple.auth",
        C4STORE_PASS_PATH => "$data_dir/simple.auth",
        C4KEYSTORE_PATH => "$data_dir/cu.def.keystore.jks",
        C4TRUSTSTORE_PATH => "$data_dir/cu.def.truststore.jks",
        C4HTTP_PORT => $http_port,
        C4SSE_PORT => $sse_port,
        C4STATE_TOPIC_PREFIX => $nm,
        C4APP_CLASS => $cl,
        C4ELECTOR_SERVERS => $elector_servers,
        C4READINESS_PATH => "",
        %$paths,
        %$add_env,
    };

    &$exec_at($dir,$env,"java","ee.cone.c4actor.ServerMain");
};

my $serve_gate = sub{
    &$exec_server({
        C4S3_CONF_DIR=>$s3conf_dir,
        C4STATE_REFRESH_SECONDS=>100,
    }, "gate", 0);
};

my $serve_main = sub{
    my($replica)=@_;
    &$exec_server({}, "main", $replica);
};

my $serve_build = sub{
    for my $dir(&$distinct(map{$$_{dir}} @$compilable_services)){
        sy("cd $dir && perl $proto_dir/build.pl");
    }
    for my $compilable_service(@$compilable_services){
        my ($dir,$nm,$mod,$cl) = &$get_tag_info($compilable_service);
        sy("cd $dir && perl $dir/.bloop/c4/compile.pl $mod");
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
    sy("mcl alias set local \$(cat $s3conf_dir/address) \$(cat $s3conf_dir/key) \$(cat $s3conf_dir/secret)");
    sleep 1 while 1;
};

my $serve_elector = sub{
    my($replica)=@_;
    &$exec_at($proto_dir,{
        C4HTTP_PORT => $elector_port_base+$replica
    }, "node","elector.js");
};

my $replicas = sub{
    my($key,$serve,$replicas)=@_;
    map{my $r=$_;("$key$r"=>sub{&$serve($r,@_)})} 0..$replicas-1
};

my $service_map = {
    build => $serve_build,
    bloop => $serve_bloop,
    zookeeper => $serve_zookeeper,
    broker => $serve_broker,
    proxy => $serve_proxy,
    node => $serve_node,
    gate => $serve_gate,
    &$replicas(main => $serve_main, 2),
    minio => $serve_minio,
    mcl => $serve_mcl,
    &$replicas(elector => $serve_elector, $elector_replicas),
};

my $init_s3 = sub{
    my $address = "http://127.0.0.1:9000";
    my($key,$secret) = map{ syf("uuidgen")=~/(\S+)/ ? "$1" : die } 0,1;
    mkdir $s3conf_dir;
    &$put_text("$s3conf_dir/address",$address);
    &$put_text("$s3conf_dir/key",$key);
    &$put_text("$s3conf_dir/secret",$secret);
};

my $init = sub{
    &$init_s3();
    &$need_certs("$data_dir/ca", "cu.broker", $data_dir, $data_dir);
    &$need_certs("$data_dir/ca", "cu.def", $data_dir);
    #
#    my ($services_by_build_dir,$build_dirs) = &$group(
#        [$repo_dir=>"main"],
#        [$proto_dir=>"gate"],
#    );
#    my @builder_service_lines = map{
#        my $dir = $_;
#        my @services = &$services_by_build_dir($dir);
#        ["build_$services[0]", "build $dir ".join(",",@services)]
#    } @$build_dirs;
    my @program_lines = map{(
        "[program:$$_[0]]",
        "command=perl $0 $$_[1]",
        "autorestart=true",
        $ENV{C4MERGE_LOGS} ? (
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
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

my $cmd_map = {
    init => $init,
};

my ($cmd,@args) = @ARGV;
($$service_map{$cmd}||$$cmd_map{$cmd})->(@args);
