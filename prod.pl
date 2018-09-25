#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ print "$_\n" and return scalar `$_` for @_ }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

my $deploy_conf = require "$ENV{C4DEPLOY_CONF}/deploy_conf.pl";
my $composes = $$deploy_conf{stacks} || die;
my $ssh_add  = sub{"ssh-add $ENV{C4DEPLOY_CONF}/id_rsa"};
my $composes_txt = "(".(join '|', sort keys %$composes).")";

my $get_compose = sub{$$composes{$_[0]}||die "composition expected"};

my $get_host_port = sub{grep{$_||die}@{$_[0]}{qw(host port dir)}};

my $ssh_ctl = sub{
    my($comp,$args)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    "ssh c4\@$host -p $port $args";
};
my $remote = sub{ 
    my($comp,$stm)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    $stm = &$stm("$dir/$comp") if ref $stm;
    "ssh c4\@$host -p $port '$stm'";
};

push @tasks, ["ssh", $composes_txt, sub{
    sy(&$ssh_add());
    sy(&$ssh_ctl($_[0],""))
}];

my $split_app = sub{
    my($app)=@_;
    $app=~/^(\w+)-(\w+)$/ ? ($1,$2) : die "<stack>-<service> expected ($app)"
};

push @tasks, ["git_init", "<proj> $composes_txt-<service>", sub{
    my($proj,$app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    my ($host,$port,$ddir) = &$get_host_port(&$get_compose($comp));
    my $repo = "$ddir/$comp/$service";
    #
    so(&$remote($comp,"mv $repo ".rand()));
    #
    my $git = "cd $repo && git ";
    sy(&$remote($comp,"mkdir -p $repo"));
    sy(&$remote($comp,"touch $repo/.dummy"));
    sy(&$remote($comp,"$git init"));
    sy(&$remote($comp,"$git config receive.denyCurrentBranch ignore"));
    sy(&$remote($comp,"$git config user.email deploy\@cone.ee"));
    sy(&$remote($comp,"$git config user.name deploy"));
    sy(&$remote($comp,"$git add .dummy"));
    sy(&$remote($comp,"$git commit -am-"));
    #
    my $bdir = "$ENV{C4DEPLOY_CONF}/$proj";
    my $adir = "$bdir/$app.adc";
    my $git_dir = "$bdir/$app.git";
    my $tmp = "$bdir/tmp";
    my $cloned = "$tmp/$service";
    #
    sy("mkdir -p $adir $tmp");
    !-e $_ or rename $_, "$tmp/".rand() or die $_ for $git_dir, $cloned;
    #
    &$put_text("$adir/vconf.json",'{"git.postCommit" : "push"}');
    sy("cd $tmp && git clone ssh://c4\@$host:$port$repo");
    sy("mv $cloned/.git $git_dir");
}];

my $list_snapshots = sub{
    my($comp,$opt)=@_;
    my $ls = &$remote($comp,"docker exec -u0 $comp\_snapshot_maker_1 ls $opt db4/snapshots");
    print "$ls\n";
    `$ls`;
};

my $get_snapshot = sub{
    my($comp,$snnm)=@_;
    my @fn = $snnm=~/^(\w{16})(-\w{8}-\w{4}-\w{4}-\w{4}-\w{12})\s*$/ ? ($1,$2) : die;
    my $zero = '0' x length $fn[0];
    sy(&$remote($comp,"docker exec -u0 $comp\_snapshot_maker_1 cat db4/snapshots/$fn[0]$fn[1]")." > $zero$fn[1]");
};

push @tasks, ["list_snapshots", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    print &$list_snapshots($comp,"-la");
}];

push @tasks, ["get_snapshot", "$composes_txt <snapshot>", sub{
    my($comp,$snnm)=@_;
    sy(&$ssh_add());
    &$get_snapshot($comp,$snnm);
}];

push @tasks, ["get_last_snapshot", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $snnm = (reverse sort &$list_snapshots($comp,""))[0];
    &$get_snapshot($comp,$snnm);
}];

my $running_containers = sub{
    my($comp)=@_;
    my $ps_stm = &$remote($comp,'docker ps --format "table {{.Names}}"');
    my ($names,@ps) = syf($ps_stm)=~/(\w+)/g;
    $names eq "NAMES" or die;
    grep{ 0==index $_,"$comp\_" } @ps;
};

my $stop = sub{
    my($comp)=@_;
    my $acc = "$comp\_frpc_1";
    ## stop all but 1
    for(0){
        my @ps = grep{$_ ne $acc} &$running_containers($comp);
        @ps or next;
        sy(&$remote($comp,"docker stop ".join " ",@ps)); 
        sleep 1;
        redo;
    }
};


push @tasks, ["put_snapshot", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $acc = "$comp\_frpc_1";
    my $remote_acc  = sub{ &$remote($comp,qq[docker exec -i $acc sh -c "$_[0]"]) };
    &$stop($comp);
    ## move db to bak
    my $db = "/c4/db4";
    my $bak = "$db/bak.".time;
    my $ls_stm = &$remote_acc("ls $db");
    my $ls = sub{ grep{!/^bak\./} syf($ls_stm)=~/(\S+)/g };
    sy(&$remote_acc("mkdir $bak"));
    sy(&$remote_acc("mv $db/$_ $bak/$_")) for &$ls();
    die $_ for &$ls();
    ## upload snapshot
    my @snapshots = grep{/^0+-/} <*>;
    @snapshots == 1 or die "not a single snapshot";
    my $snapdir = "$db/snapshots";
    sy(&$remote_acc("mkdir $snapdir && cat > $snapdir/$snapshots[0]")." < $snapshots[0]");
    sy(&$remote_acc("chown -R c4:c4 $snapdir"));
}];

#push @tasks, ["clear_snapshots", $composes_txt, sub{
#    my($comp)=@_;
#    sy(&$ssh_add());
#    my $remote_sm = sub{ &$remote($_[0],qq[docker exec -u0 $_[0]_snapshot_maker_1 $_[1]]) };
#    my $cmd = &$remote_sm($comp,'find db4/snapshots -printf "%A@ %p\n"');
#    print "$cmd\n";
#    my @lines = reverse sort `$cmd`;
#    my @snaps = map{ m[^(\d{10})\.\d+\s(db4/snapshots/\w{16}-\w{8}-\w{4}-\w{4}-\w{4}-\w{12})\s*$] ? [$1,$2] : () } @lines;
#    my @old = sub{@_[20..$#_]}->(@snaps);
#    my %byday;
#    push @{$byday{sub{sprintf "%04d-%02d-%02d",$_[5]+1900,$_[4]+1,$_[3]}->(gmtime($$_[0]))}||=[]}, $$_[1] for @old;
#    for my $date(sort keys %byday){
#        my $paths = $byday{$date}||die;
#        sy(&$remote_sm($comp,"tar -czf db4/snapshots/.arch-$date.tar.gz $$paths[0]"));
#        sy(&$remote_sm($comp,"rm ".join(' ',@$paths)));
#    }
#}];

push @tasks, ["gc","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    for my $c(&$running_containers($comp)){
        #print "container: $c\n";
        my $cmd = &$remote($comp,"docker exec $c jcmd");
        for(`$cmd`){
            my $pid = /^(\d+)/ ? $1 : next;
            /JCmd/ && next;
            sy(&$remote($comp,"docker exec $c jcmd $pid GC.run"));
        }
    }
}];

push @tasks, ["stop", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    &$stop($comp);
}];

my $restart = sub{
    my($app,$cmds)=@_;
    my($comp,$service) = &$split_app($app);
    my $container = "$comp\_$service\_1";
    sy(&$remote($comp,sub{"cd $_[0]/$service && git reset --hard $cmds && docker restart $container && docker logs $container -ft --tail 2000"}));
};

my $remote_single_cmd = sub{
    my($app, $cmds)=@_;
    my($comp,$service) = &$split_app($app);
    sy(&$remote($comp,sub{"cd $_[0]/$service && $cmds"}));
};

push @tasks, ["restart","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$remote_single_cmd($app,"git reset --hard");
    &$restart($app,"");
}];

push @tasks, ["revert_list","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$remote_single_cmd($app,'git log --format=format:"%H  %ad  %ar  %an" --date=local --reverse');
    print "\n";
}];
push @tasks, ["revert_to","$composes_txt-<service> <commit>",sub{
    my($app,$commit)=@_;
    sy(&$ssh_add());
    my $time = time;
    &$restart($app," && git checkout $commit -b $commit-$time");
}];
push @tasks, ["revert_off","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$restart($app," && git checkout master");
}];

my $user = "c4";
my $rsync_to = sub{
    my($from_path,$comp,$to_path)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    "rsync -e 'ssh -p $port' -a $from_path $user\@$host:$to_path";
};
my $put_temp = sub{
    my($fn,$text)=@_;
    my $path = "/tmp/$$-$fn";
    &$put_text($path,$text);
    print "generated $path\n";
    $path;
};
my $put_compose = sub{ &$put_temp("docker-compose.yml",$_[0]) };
my $docker_compose = sub{
    my($run_comp,$yml_path,$add)=@_;
    &$remote($run_comp,"docker-compose -f - -p $run_comp up -d --remove-orphans $add")." < $yml_path";
};

#### composer

use YAML::XS qw(LoadFile DumpFile Dump);
$YAML::XS::QuoteNumericStrings = 1;

my $inbox_prefix = '';
my $bin = "kafka/bin";

my $bootstrap_server = "broker:9092";
my @c_script = ("inbox_configure.pl","purger.pl");


# pass src commit
# migrate states
# >2 >4
# fix kafka configs
# move settings to scala

my %merge;
my $merge = sub{&{$merge{join "-",map{ref}@_}||sub{$_[$#_]}}};
$merge{"HASH-HASH"} = sub{
    my($b,$o)=@_;
    +{map{
        my $k = $_;
        ($k=>&$merge(map{(exists $$_{$k})?$$_{$k}:()} $b,$o));
    } keys %{+{%$b,%$o}}};
};
$merge{"ARRAY-ARRAY"} = sub{[map{@$_}@_]};

my $extract_env = sub{
    my($opt) = @_;
    my %env = map{/^C4/?($_=>$$opt{$_}):()} keys %$opt;
    my %def = map{/^C4/?():($_=>$$opt{$_})} keys %$opt;
    &$merge({environment => \%env}, \%def);
};

my $app_user = sub{
    my %opt = @_;
    (user=>$user, working_dir=>"/$user");
};

my $volumes = sub{map{"$_:/$user/$_"}@_};

my $without_local_db_template_yml = sub{+{
    services => {
        frpc => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["frp/frpc","-c","/c4deploy/frpc.ini"],
            volumes => [&$volumes("db4")],
            C4DEPLOY_LOCAL => 1,
            networks => { default => { aliases => ["broker"] } },
        },
    },
}};

my $with_local_db_template_yml = sub{+{
    services => {
        zookeeper => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["$bin/zookeeper-server-start.sh","zookeeper.properties"],
            volumes => [&$volumes("db4")],
        },
        broker => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["$bin/kafka-server-start.sh","server.properties"],
            depends_on => ["zookeeper"],
            volumes => [&$volumes("db4")],
        },
        inbox_configure => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["perl",$c_script[0]],
            depends_on => ["broker"],
        },
        gate => {
            C4APP_IMAGE => "gate-server",
            C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.HttpGatewayApp",
            C4STATE_REFRESH_SECONDS => 100,
        },
        snapshot_maker => {
            C4APP_IMAGE => "gate-server",
            C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.SnapshotMakerApp",
            #restart => "on-failure",
        },
        purger => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["perl",$c_script[1]],
            tty => "true",
            volumes => [&$volumes("db4")],
        },
        haproxy => {
            C4APP_IMAGE => "haproxy",
            C4EXPOSE_HTTP_PORT => 80,
            expose => [80],
        },
        frpc => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["frp/frpc","-c","/c4deploy/frpc.ini"],
            volumes => [&$volumes("db4")],
            C4DEPLOY_LOCAL => 1,
        },
    },
}};

my $remote_build = sub{
    my($build_comp,$dir,$nm,$tag)=@_;
    my $build_temp = syf("hostname")=~/(\w+)/ ? "c4build_temp/$1" : die;
    sy(&$remote($build_comp,"mkdir -p $build_temp"));
    sy(&$rsync_to("$dir/$nm",$build_comp,$build_temp));
    sy(&$remote($build_comp,"docker build -t $tag $build_temp/$nm"));
};

my $to_pairs = sub{
    my @arg=@_;
    map{$_%2==0?[@arg[$_,$_+1]]:()} 0..$#arg
};

my $to_ini_file = sub{
    my($c)=@_;
    join "", map{"$_\n"}
        map{("[$$_[0]]",map{"$$_[0] = $$_[1]"}&$to_pairs(@{$$_[1]||die}))}
        &$to_pairs(@$c);
};

my $compose_up = sub{
    my($mode,$run_comp,$conf,$frpc_conf)=@_;
    my $build_comp = $$conf{builder} || $run_comp;
    my $is_full = $mode eq 'fast' ? 0 : $mode eq 'full' ? 1 : die "fast|full?";
    my $template = { volumes => { db4 => {} }, version => "3.2" };
    my $override = &$merge($template, $conf);
    my $override_services = $$override{services} || die;
    my %was;
    my $deploy_dir = "$$conf{dir}/$run_comp";
    my $generated_services = {map{
        my $service_name = $_;
        my $service = $$override_services{$service_name} || die;
        my $img = $$service{C4APP_IMAGE} || $service_name;
        my $build_parent_dir = (grep{-e "$_/$img/Dockerfile"} $ENV{C4DOCKERFILE_PATH}=~/[^:]+/g)[0];
        my $tag = "c4-$run_comp-$img";
        $is_full and $build_parent_dir and !($was{$img}++)
            and &$remote_build($build_comp,$build_parent_dir,$img,$tag);
        ##todo: !$need_commit or `cat $dockerfile`=~/c4commit/ or die "need commit and rebuild";
        my $generated_service = {
            restart=>"unless-stopped",
            ($$service{C4STATE_TOPIC_PREFIX}?(
                &$app_user(),
                $$conf{main} ? () : (depends_on => ["broker"]),
                C4BOOTSTRAP_SERVERS => $bootstrap_server,
                C4INBOX_TOPIC_PREFIX => $inbox_prefix,
            ):()),
            volumes => [
                $$service{C4STATE_TOPIC_PREFIX} ? &$volumes("db4") : (),
                $$service{C4DEPLOY_LOCAL} ? "$deploy_dir/$service_name:/c4deploy" : (),
            ],
            ($$service{C4EXPOSE_HTTP_PORT} && $$conf{http_port} ? (
                ports => ["$$conf{http_port}:$$service{C4EXPOSE_HTTP_PORT}"],
            ):()),
            image => $tag
        };
        ($service_name => &$extract_env(&$merge($generated_service,$service)));
    } keys %$override_services };
    my $generated = { %$template, services => $generated_services };

    #DumpFile("docker-compose.yml",$generated);
    my $yml_str = Dump($generated);
    #$text=~s/(\n\s+-\s+)([^\n]*\S:\d\d)/$1"$2"/gs;
    $yml_str=~s/\b(tty:\s)'(true)'/$1$2/;
    ##todo: fix need_commit; ...[some.yml]
    if($is_full && $build_comp ne $run_comp){
        my %images = map{$_?($_=>1):()} map{$$_{image}} values %$generated_services;
        my $images_str = join " ", sort keys %images;
        sy(&$remote($build_comp,"docker save $images_str").' | '.&$remote($run_comp,"docker load"));
    }
    my $tmp_frpc_path = &$put_temp("frpc.ini",&$to_ini_file($frpc_conf));
    sy(&$remote($run_comp,"mkdir -p $deploy_dir/frpc"));
    sy(&$rsync_to($tmp_frpc_path,$run_comp,"$deploy_dir/frpc/frpc.ini"));
    sy(&$docker_compose($run_comp,&$put_compose($yml_str),""));
};

my $split_port = sub{ $_[0]=~/^(\S+):(\d+)$/ ? ($1,$2) : die };

my $frp_auth_all = require "$ENV{C4DEPLOY_CONF}/frp_auth.pl";
my $get_frp_sk = sub{($$frp_auth_all{$_[0]}||die)->[1]||die};
my $get_frp_common = sub{
    my($comp)=@_;
    my($token,$sk) = ($$frp_auth_all{$comp}||die $comp)->[0]||die;
    my $conf = &$get_compose($comp);
    my($frps_addr,$frps_port) = &$split_port($$conf{frps}||die);
    my $proxy = $$conf{frp_http_proxy};
    (
        server_addr => $frps_addr,
        server_port => $frps_port,
        token => $token,
        user => $comp,
        $proxy ? (http_proxy => $proxy) : (),
    );
};


push @tasks, ["compose_up","fast|full $composes_txt",sub{
    my($mode,$run_comp)=@_;
    sy(&$ssh_add());
    my $conf = &$get_compose($run_comp);
    my $main_comp = $$conf{main};
    my($broker_ip,$broker_port) = &$split_port($bootstrap_server);
    if($main_comp){
        my $ext_conf = &$merge(&$without_local_db_template_yml(),$conf);
        my $frpc_conf = [
            common => [&$get_frp_common($main_comp)],
            broker_visitor => [
                type => "stcp",
                role => "visitor",
                sk => &$get_frp_sk($main_comp),
                server_name => "broker", #$main_comp.broker?
                bind_port => $broker_port,
                bind_addr => "0.0.0.0",
            ],
        ];
        &$compose_up($mode,$run_comp,$ext_conf,$frpc_conf);
    } else {
        my $ext_conf = &$merge(&$with_local_db_template_yml(),$conf);
        my $frpc_conf = [
            common => [&$get_frp_common($run_comp)],
            broker => [
                type => "stcp",
                sk => &$get_frp_sk($run_comp),
                local_ip => $broker_ip,
                local_port => $broker_port,
            ],
        ];
        &$compose_up($mode,$run_comp,$ext_conf,$frpc_conf);
    }
}];

#### proxy

my $mk_from_cfg = sub{
my($conf)=@_;
my($ts,$gate_addr,$sni_postfix) = map{$$conf{$_}||die} qw[items gate sni_prefix];
qq{
global
  tune.ssl.default-dh-param 2048
defaults
  timeout connect 5s
  timeout client  3d
  timeout server  3d
  mode tcp
}.join('',map{qq{listen listen_$$_[1]
  bind $$_[0]
  server s_$$_[1] $gate_addr ssl verify none sni str("$$_[1].$sni_postfix")
}}@$ts);
};

my $mk_from_yml = sub{
my($fn)=@_;
qq{
services:
  haproxy:
    image: "haproxy:1.7"
    userns_mode: "host"
    network_mode: "host"
    restart: unless-stopped
    volumes:
    - "$fn:/usr/local/etc/haproxy/haproxy.cfg:ro"
version: '3.2'
};
};

my $mk_to_cfg = sub{
my($conf,$ts)=@_;
my($to_ssl_host,$lis) = map{$$conf{$_}||die} qw[external_ip plain_items];
qq{
global
  tune.ssl.default-dh-param 2048
  log "logger:514" local0
defaults
  timeout connect 5s
  timeout client  3d
  timeout server  3d
  mode tcp
  option tcplog
  log global
frontend fe80
  mode http
  bind :80
  acl a_letsencrypt path_beg /.well-known/acme-challenge/
  use_backend beh_letsencrypt if a_letsencrypt
  redirect scheme https if !{ method POST } !a_letsencrypt
}.join('',map{$$_[1]!~/:80$/?():qq{  use_backend behttp_$$_[0] if { hdr_dom(host) $$_[0] }
}}@$ts).qq{
  default_backend beh_letsencrypt
backend beh_letsencrypt
  mode http
  server s_letsencrypt $to_ssl_host:8080
frontend fe443
  bind :443 ssl crt-list /etc/letsencrypt/haproxy/list.txt
}.join('',map{qq{  use_backend be_$$_[0] if { ssl_fc_sni -m dom $$_[0] }
}}@$ts).qq{
}.join('',map{qq{backend be_$$_[0]
  server s_$$_[0] $$_[1]
}}@$ts).qq{
}.join('',map{$$_[1]!~/:80$/?():qq{backend behttp_$$_[0]
  mode http
  server s_$$_[0] $$_[1]
}}@$ts).qq{
}.join('',map{ my $port=/(\d+)$/?$1:die; qq{listen listen_$port
  bind :$port
  server s_simple_$port $_
}}@$lis).qq{
};
};

my $mk_to_yml = sub{
my($fn,$conf)=@_;
my($to_ssl_host,$li_host,$lis) = map{$$conf{$_}||die} qw[external_ip internal_ip plain_items];
qq{
services:
  haproxy:
    image: "haproxy:1.7"
    restart: unless-stopped
    volumes:
    - "$fn/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"
    - "certs:/etc/letsencrypt:ro"
    ports:
    - "$to_ssl_host:443:443"
    - "$to_ssl_host:80:80"}.join('',map{ my $port=/(\d+)$/?$1:die; qq{
    - "$li_host:$port:$port"}}@$lis).qq{
  logger:
    image: c4logger
    restart: unless-stopped
    command: /usr/sbin/syslog-ng -F -f /syslog-ng.conf
  certbot:
    image: c4certbot
    restart: unless-stopped
    command: sh -c 'certbot renew --preferred-challenges http && sleep 1d'
    volumes:
    - "certs:/etc/letsencrypt"
    ports:
    - "$to_ssl_host:8080:80"
  frps:
    image: c4frp
    restart: unless-stopped
    command: ["/frps","-c","/frps.ini"]
    volumes:
    - "$fn/frps.ini:/frps.ini:ro"
    ports:
    - "7000:7000"
version: '3.2'
volumes:
  certs: {}
};
};#$to_ssl_host:

my $hmap = sub{ my($h,$f)=@_; map{&$f($_,$$h{$_})} sort keys %$h };


push @tasks, ["proxy_to","up|test",sub{
    my($mode)=@_;
    my $conf = $$deploy_conf{proxy_to} || die;
    my $comp = $$conf{stack} || die;
    my $dir = (&$get_compose($comp)->{dir}||die)."/$comp";
    my @sb_items = &$hmap($composes, sub{ my($comp,$comp_conf)=@_;
        $$comp_conf{proxy_dom} ? do{
            my $host = $$comp_conf{host}||die;
            my $port = $$comp_conf{http_port}||die;
            [$$comp_conf{proxy_dom},"$host:$port"]
        } : ()
    });
    my $tmp_cfg_path = &$put_temp("haproxy.cfg",&$mk_to_cfg($conf,[@{$$conf{items}||[]},@sb_items]));
    my %common = &$get_frp_common($comp);
    my $tmp_frps_path = &$put_temp("frps.ini",&$to_ini_file([common=>[
        token=>$common{token},
    ]]));
    my $tmp_yml_path = &$put_compose(&$mk_to_yml($dir,$conf));
    if($mode eq "up"){
        sy(&$ssh_add());
        sy(&$remote($comp,"mkdir -p $dir"));
        sy(&$rsync_to($tmp_cfg_path,$comp,"$dir/haproxy.cfg"));
        sy(&$rsync_to($tmp_frps_path,$comp,"$dir/frps.ini"));
        sy(&$docker_compose($comp,$tmp_yml_path," --force-recreate"));
    }
}];

push @tasks, ["proxy_from"," ",sub{
    my $conf = $$deploy_conf{proxy_from} || die;
    &$put_temp("from-haproxy.cfg",&$mk_from_cfg($conf));
    &$put_compose(&$mk_from_yml("./from-haproxy.cfg"));
    print "docker-compose -p proxy up -d --remove-orphans --force-recreate\n"
}];

push @tasks, ["cert","<hostname>",sub{
  my($nm)=@_;
  sy(&$ssh_add());
  my $conf = $$deploy_conf{proxy_to} || die;
  my($comp,$cert_mail) = map{$$conf{$_}||die} qw[stack cert_mail];
  my $exec = sub{&$remote($comp,"docker exec -i proxy_certbot_1 sh").' < '.&$put_temp(@_)};
  sy(&$exec("cert-only.sh","certbot certonly --standalone -n --email '$cert_mail' --agree-tos -d $nm")) if $nm;
  my $live = "/etc/letsencrypt/live";
  my $ha = "/etc/letsencrypt/haproxy";
  my @hosts = sort{$a cmp $b} syf(&$exec("cert-list.sh","ls $live"))=~/(\S+)/g;
  sy(&$exec("cert-join.sh",join' && ',
    "mkdir -p $ha",
    (map{"cat $live/$_/fullchain.pem $live/$_/privkey.pem > $ha/$_.pem"} @hosts),
    "> $ha/list.txt",
    (map{"echo $ha/$_.pem >> $ha/list.txt"} @hosts),
  ));
  sy(&$remote($comp,"docker exec proxy_haproxy_1 kill -s HUP 1"));
}];

####

my $tp_split = sub{ "$_[0]\n\n"=~/(.*?\n\n)/gs };

my $tp_run = sub{
    my($pkg,$wrap)=@_;
    my $cmd = &$wrap("jcmd");
    my @pid = map{/^(\d+)\s+(\S+)/ && index($2, $pkg)==0?"$1":()} `$cmd`;
    my $pid = $pid[0] || return;
    while(1){
        select undef, undef, undef, 0.25;
        print grep{ !/\.epollWait\(/ && /\sat\s/ } &$tp_split(syf(&$wrap("jcmd $pid Thread.print")));
    }
};

push @tasks, ["thread_print_local","<package>",sub{
    my($pkg)=@_;
    &$tp_run($pkg,sub{"$_[0]"});
}];
push @tasks, ["thread_print","$composes_txt-<service> <package>",sub{
    my($app,$pkg)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    &$tp_run($pkg,sub{ &$remote($comp,"docker exec $comp\_$service\_1 $_[0]") });#/RUNNABLE/
}];
push @tasks, ["thread_grep_cut","<substring>",sub{
    my($v)=@_;
    print map{ my $i = index $_,$v; $i<0?():substr($_,0,$i)."\n\n" } &$tp_split(join '',<STDIN>);
}];
push @tasks, ["thread_grep_not","<substring>",sub{
    my($v)=@_;
    print grep{ 0 > index $_,$v } &$tp_split(join '',<STDIN>);
}];
push @tasks, ["thread_count"," ",sub{
    my @r = grep{/\S/} &$tp_split(join '',<STDIN>);
    print scalar(@r)."\n";
}];

####

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[2]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks;
}

#userns_mode: "host"
