#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ print "$_\n" and return scalar `$_` for @_ }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

my $get_deploy_conf = lazy{ require "$ENV{C4DEPLOY_CONF}/deploy_conf.pl" };
my $ssh_add  = sub{"ssh-add $ENV{C4DEPLOY_CONF}/id_rsa"};
my $composes_txt = "<stack>";

my $get_compose = sub{&$get_deploy_conf()->{stacks}{$_[0]}||die "composition expected"};

my $get_host_port = sub{grep{$_||die}@{$_[0]}{qw(host port dir)}};

my $ssh_ctl = sub{
    my($comp,@args)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    ("ssh","c4\@$host","-p$port",@args);
};
my $remote = sub{ 
    my($comp,$stm)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    $stm = &$stm("$dir/$comp") if ref $stm;
    "ssh c4\@$host -p $port '$stm'"; #'' must be here; ssh joins with ' ' args for the remote sh anyway
};

my $remote_interactive = sub{
    my($comp,$add,$stm)=@_;
    &$remote($comp,"docker exec -i $add sh -c \"$stm\"");
};

my $running_containers_all = sub{
    my($comp)=@_;
    my $ps_stm = &$remote($comp,'docker ps --format "table {{.Names}}"');
    my ($names,@ps) = syf($ps_stm)=~/(\w+)/g;
    $names eq "NAMES" or die;
    @ps;
};

my $user = "c4";
my $rsync_to = sub{
    my($from_path,$comp,$to_path,$opt)=@_;
    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
    sy(&$remote($comp,"mkdir -p $to_path"));
    sy("rsync -e 'ssh -p $port' -a $opt $from_path $user\@$host:$to_path");
};
my $put_temp = sub{
    my($fn,$text)=@_;
    my $path = "/tmp/$$-$fn";
    &$put_text($path,$text);
    print "generated $path\n";
    $path;
};
my $need_path = sub{
    my($dn)=@_;
    $dn=~s{/[^/]*$}{} and sy("mkdir -p $dn");
    $_[0];
};
my $tmp_dir_count=0;
my $get_tmp_path; $get_tmp_path = sub{
    my($c)=@_;
    my $path = "/tmp/$$-$c";
    (-e $path) ? &$get_tmp_path($c+1) : $path;
};
my $rsync_start = sub{
    my $path = &$get_tmp_path(0);
    (sub{
        my($fn)=@_;
        &$need_path("$path/$fn")
    },sub{
        my($comp)=@_;
        my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
        &$rsync_to(&$need_path("$path/"),$comp,"$dir/$comp","");
    })
};

####

push @tasks, ["agent","<command-with-args>",sub{
    my(@args)=@_;
    sy(&$ssh_add());
    sy(@args);
}];

push @tasks, ["ssh", "$composes_txt [command-with-args]", sub{
    my($comp,@args)=@_;
    sy(&$ssh_add());
    sy(&$ssh_ctl($comp,@args));
}];

my $split_app = sub{
    my($app)=@_;
    $app=~/^(\w+)-(\w+)$/ ? ($1,$2) : die "<stack>-<service> expected ($app)"
};

my $docker_compose_up = sub{
    my($run_comp,$add)=@_;
    &$remote($run_comp,sub{"cd $_[0] && docker-compose up -d --remove-orphans $add"});
};

my $git_info = sub{
    my($app)=@_;
    my($comp,$service) = &$split_app($app);
    my ($host,$port,$ddir) = &$get_host_port(&$get_compose($comp));
    my $repo = "$ddir/$comp/$service";
    ($comp,$service,$repo,"ssh://c4\@$host:$port$repo")
};

my $git_init_remote = sub{
    my($proj,$app)=@_;
    my($comp,$service,$repo,$r_repo) = &$git_info($app);
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
};

my $git_init_local = sub{
    my($proj,$app)=@_;
    my($comp,$service,$repo,$r_repo) = &$git_info($app);
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
    &$put_text("$adir/vconf.json",'{}'); #"git.postCommit" : "push"
    sy("cd $tmp && git clone $r_repo");
    sy("mv $cloned/.git $git_dir");
};

push @tasks, ["git_init", "<proj> $composes_txt-<service>", sub{
    my($proj,$app)=@_;
    sy(&$ssh_add());
    &$git_init_remote($proj,$app);
    &$git_init_local($proj,$app);
}];

push @tasks, ["git_init_local", "<proj> $composes_txt-<service>", sub{
    my($proj,$app)=@_;
    sy(&$ssh_add());
    &$git_init_local($proj,$app);
}];

my $remote_acc  = sub{
    my($comp,$stm)=@_;
    &$remote($comp,"docker exec -uc4 $comp\_frpc_1 $stm");
};

my $list_snapshots = sub{
    my($comp,$opt)=@_;
    my $ls = &$remote_acc($comp,"ls $opt /c4/db4/snapshots");
    print "$ls\n";
    `$ls`;
};

my $get_sm_binary = sub{
    my($comp,$from,$to)=@_;
    &$remote_acc($comp,"cat $from")." > $to";
};

my $snapshot_name = sub{
    my($snnm)=@_;
    my @fn = $snnm=~/^(\w{16})(-\w{8}-\w{4}-\w{4}-\w{4}-\w{12}[-\w]*)\s*$/ ? ($1,$2) : die;
    my $zero = '0' x length $fn[0];
    ("$fn[0]$fn[1]","$zero$fn[1]")
};

my $get_snapshot = sub{
    my($comp,$snnm,$mk_path)=@_;
    my($fn,$zfn) = &$snapshot_name($snnm);
    &$get_sm_binary($comp,"/c4/db4/snapshots/$fn",&$mk_path($zfn));
};

push @tasks, ["list_snapshots", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    print &$list_snapshots($comp,"-la");
}];

push @tasks, ["get_snapshot", "$composes_txt <snapshot>", sub{
    my($comp,$snnm)=@_;
    sy(&$ssh_add());
    sy(&$get_snapshot($comp,$snnm,sub{$_[0]}));
}];

push @tasks, ["get_last_snapshot", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $snnm = (reverse sort &$list_snapshots($comp,""))[0];
    sy(&$get_snapshot($comp,$snnm,sub{$_[0]}));
}];

my $running_containers = sub{
    my($comp)=@_;
    grep{ 0==index $_,"$comp\_" } &$running_containers_all($comp);
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

my $move_db_to_bak = sub{
    my($comp)=@_;
    &$stop($comp);
    ## move db to bak
    my $db = "/c4/db4";
    my $bak = "$db/bak.".time;
    my $ls_stm = &$remote_acc($comp,"ls $db");
    my $ls = sub{ grep{!/^bak\./} syf($ls_stm)=~/(\S+)/g };
    sy(&$remote_acc($comp,"mkdir $bak"));
    sy(&$remote_acc($comp,"mv $db/$_ $bak/$_")) for &$ls();
    die $_ for &$ls();
};

my $db4put_start = sub{
    my($mk_path,$sync) = &$rsync_start();
    (sub{
        &$mk_path("frpc/db4ini/$_[0]");
    },sub{
        my($comp)=@_;
        so(&$remote($comp,sub{"rm -r $_[0]/frpc/db4ini"}));
        &$sync($comp);
        sy(&$remote_acc($comp,"rsync -a /c4deploy/db4ini/ /c4/db4"));
    })
};

push @tasks, ["put_snapshot", "$composes_txt <file_path>", sub{
    my($comp,$path)=@_;
    sy(&$ssh_add());
    my($mk_path,$sync) = &$db4put_start();
    my($fn,$zfn) = &$snapshot_name($path=~m{([^/]+)$} ? $1 : die "bad snapshot name");
    sy("cp $path ".&$mk_path("snapshots/$zfn"));
    &$move_db_to_bak($comp);
    &$sync($comp);
    sy(&$docker_compose_up($comp,""));
}];

#push @tasks, ["snapshot_put", "$composes_txt <file_path>", sub{
#    my($comp,$path)=@_;
#    sy(&$ssh_add());
#    my($mk_path,$sync) = &$db4put_start();
#    my($fn,$zfn) = &$snapshot_name($path=~m{([^/]+)$} ? $1 : die "bad snapshot name");
#    sy("cp $path ".&$mk_path("snapshot_targets/$zfn"));
#    &$sync($comp);
#}];

#push @tasks, ["snapshot_debug", "$composes_txt <tx>", sub{
#    my($comp,$offset)=@_;
#    $offset || die 'missing tx';
#    sy(&$ssh_add());
#    my $conf = &$get_compose($comp);
#    my $main = $$conf{main} || die;
#    my $env = join(" ",
#        "C4INBOX_TOPIC_PREFIX=$main",
#        "C4STATE_TOPIC_PREFIX=ee.cone.c4gate.DebugSnapshotMakerApp",
#        "C4SNAPSHOTS_URL=http://frpc:7980/snapshots",
#        "C4MAX_REQUEST_SIZE=25000000",
#        "C4DEBUG_OFFSET=$offset",
#    );
#    sy(&$remote($comp,qq{docker exec $comp\_snapshot_maker_1 sh -c "$env app/bin/c4gate-server"}));
#}];

#sy(&$remote_acc($comp,"chown -R c4:c4 $dir"));
#push @tasks, ["debug_snapshot", "<from-stack> <to-stack> <tx>", sub{
#    my($from_comp,$to_comp,$offset)=@_;
#    sy(&$ssh_add());
#    my($from_mk_path,$from_sync) = &$db4put_start();
#    &$put_text(&$from_mk_path("debug_options/request"),$offset);
#    &$from_sync($from_comp);
#    sy(&$remote($from_comp,"docker restart $from_comp\_snapshot_maker_1"));
#    my $dir = "/c4/db4/debug_options";
#    sleep 3 while syf(&$remote_acc($from_comp,"ls $dir/request"))=~/\S/;
#    my $snap_fn = syf(&$remote_acc($from_comp,"cat $dir/response"))=~/(\S+)/ ? $1 : die;
#    my($mk_path,$sync) = &$db4put_start();
#    sy(&$get_snapshot($from_comp,$snap_fn,sub{&$mk_path("snapshots/$_[0]")}));
#    sy(&$get_sm_binary($from_comp,"$dir/response-event",&$mk_path("debug_options/request-event")));
#    &$move_db_to_bak($to_comp);
#    &$sync($to_comp);
#    sy(&$docker_compose_up($to_comp,""));
#}];
#put req
#restart
#poll resp path
#get snapshot, event
#stop, clear
#put snapshot, event
#up

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

my $git_with_dir = sub{
    my($app,$args)=@_;
    my $conf_dir = $ENV{C4DEPLOY_CONF} || die;
    my ($git_dir,@git_dirs) = grep{-e} map{"$_/$app.git"} <$conf_dir/*>;
    $git_dir && !@git_dirs or die "bad git-dir count for $app";
    my $work_tree = &$get_tmp_path(0);
    "mkdir $work_tree && git --git-dir=$git_dir --work-tree=$work_tree $args";
};

my $restart = sub{
    my($app)=@_;
    sy(&$git_with_dir($app,"push"));
    my($comp,$service) = &$split_app($app);
    my $container = "$comp\_$service\_1";
    sy(&$remote($comp,sub{"cd $_[0]/$service && git reset --hard && docker restart $container && docker logs $container -ft --tail 2000"}));
};

push @tasks, ["restart","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$restart($app,"");
}];

push @tasks, ["revert_list","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$git_with_dir($app,'log --format=format:"%H  %ad  %ar  %an" --date=local --reverse'));
    print "\n";
}];

push @tasks, ["revert_to","$composes_txt-<service> <commit>",sub{
    my($app,$commit)=@_;
    sy(&$ssh_add());
    $commit || die "no commit";
    sy(&$git_with_dir($app,"revert --no-edit $commit..HEAD"));
    &$restart($app);
}];
#to: && git checkout $commit -b $commit-$time
#off: && git checkout master

my $docker_compose_start = sub{
    my($content)=@_;
    my($mk_path,$sync) = &$rsync_start();
    my $put = sub{
        my($path,$cont)=@_;
        &$put_text(&$mk_path($path),$cont);
    };
    &$put("docker-compose.yml",$content);
    ($put,$sync);
};

#### composer

use YAML::XS qw(LoadFile DumpFile Dump);
$YAML::XS::QuoteNumericStrings = 1;

#my $inbox_prefix = '';
my $bin = "kafka/bin";

my $bootstrap_server = "broker:9092";
my $zookeeper_server = "zookeeper:2181";
my $http_server = "gate:8067";
my $vhost_http_port = "80";
my $vhost_https_port = "443";
#my $parent_http_server = "frpc:8067";

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

my $common_services = sub{(
    gate => {
        C4APP_IMAGE => "gate-server",
        C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.HttpGatewayApp",
        C4MAX_REQUEST_SIZE => "250000000",
        C4STATE_REFRESH_SECONDS => 100,
        volumes => ["db4:/c4/db4"],
        C4DEPLOY_LOCAL => 1,
    },
    haproxy => {
        C4APP_IMAGE => "haproxy",
        C4EXPOSE_HTTP_PORT => 80,
        expose => [80],
        C4DEPLOY_LOCAL => 1,
        C4SU => 1,
    },
    zookeeper => {
        C4APP_IMAGE => "zoo",
        command => ["$bin/zookeeper-server-start.sh","zookeeper.properties"],
        volumes => ["db4:/c4/db4"],
    },
    broker => {
        C4APP_IMAGE => "zoo",
        command => ["$bin/kafka-server-start.sh","server.properties"],
        depends_on => ["zookeeper"],
        volumes => ["db4:/c4/db4"],
    },
    frpc => {
        C4APP_IMAGE => "zoo",
        command => ["frp/frpc","-c","/c4deploy/frpc.ini"],
        volumes => ["db4:/c4/db4"],
        C4DEPLOY_LOCAL => 1,
    },
)};

my $template_yml = sub{+{
    services => {
        &$common_services(),
    },
}};

#networks => { default => { aliases => ["broker","zookeeper"] } },

my $remote_build = sub{
    my($build_comp,$dir,$nm,$tag)=@_;
    my $build_temp = syf("hostname")=~/(\w+)/ ? "c4build_temp/$1" : die;
    &$rsync_to("$dir/$nm",$build_comp,$build_temp,"--del");
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
    my($mode,$run_comp,$conf,$frpc_conf,$simple_auth)=@_;
    my $build_comp = $$conf{builder} || $run_comp;
    my $is_full = $mode eq 'fast' ? 0 : $mode eq 'full' ? 1 : die "fast|full?";
    my $template = { volumes => { db4 => {} }, version => "3.2" };
    my $override = &$merge($template, $conf);
    my $override_services = $$override{services} || die;
    my %was;
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
            logging => {
                driver => "json-file",
                options => {
                    "max-size" => "20m",
                    "max-file" => "20",
                },
            },
            $$service{C4SU} ? () : (user=>"c4", working_dir=>"/c4"),
            ($$service{C4STATE_TOPIC_PREFIX}?(
                #$$conf{main} ? () :
                (depends_on => ["broker"]),
                C4BOOTSTRAP_SERVERS => $bootstrap_server,
                C4MAX_REQUEST_SIZE => "250000000",
                C4INBOX_TOPIC_PREFIX => "",
                C4HTTP_SERVER => "http://$http_server",
                #C4PARENT_HTTP_SERVER => "http://$parent_http_server",
                C4AUTH_KEY_FILE => "/c4deploy/simple.auth", #gate does no symlinks
                tty => "true",
            ):()),
            volumes => [
                $$service{C4DEPLOY_LOCAL} ? "./$service_name:/c4deploy" : (),
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
    $yml_str=~s/\b(tty:\s)'(true)'/$1$2/g;
    ##todo: fix need_commit; ...[some.yml]
    if($is_full && $build_comp ne $run_comp){
        my %images = map{$_?($_=>1):()} map{$$_{image}} values %$generated_services;
        my $images_str = join " ", sort keys %images;
        sy(&$remote($build_comp,"docker save $images_str").' | '.&$remote($run_comp,"docker load"));
    }
    my($put,$sync) = &$docker_compose_start($yml_str);
    for my $k(keys %$generated_services){
        my $env = ($$generated_services{$k} || die)->{environment}||next;
        $$env{C4STATE_TOPIC_PREFIX} && $$env{C4DEPLOY_LOCAL} or next;
        &$put("$k/simple.auth",$simple_auth);
    }
    &$put("frpc/frpc.ini",&$to_ini_file($frpc_conf));
    #
    my $cert_path = &$get_tmp_path(0);
    sy(qq[openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout $cert_path.key -out $cert_path.crt -subj "/C=EE"]);
    &$put("haproxy/dummy.pem",syf("cat $cert_path.crt $cert_path.key"));
    #
    &$sync($run_comp);
    sy(&$docker_compose_up($run_comp,""));
};

my $split_port = sub{ $_[0]=~/^(\S+):(\d+)$/ ? ($1,$2) : die };

my $frp_auth_all = require "$ENV{C4DEPLOY_CONF}/frp_auth.pl";
my $get_frp_sk = sub{($$frp_auth_all{$_[0]}||die)->[1]||die};
my $get_frp_common = sub{
    my($comp)=@_;
    my($token,$sk) = ($$frp_auth_all{$comp}||die "$comp frp auth not found")->[0]||die "frp auth not found";
    my $conf = &$get_compose($comp);
    my($frps_addr,$frps_port) = &$split_port($$conf{frps}||die);
    my $proxy = $$conf{frp_http_proxy};
    (
        server_addr => $frps_addr,
        server_port => $frps_port,
        token => $token,
        $proxy ? (http_proxy => $proxy) : (),
    );
};

my $frp_visitor = sub{
    my($comp,$server)=@_;
    my($name,$port) = &$split_port($server);
    ("$comp.$name\_visitor" => [
        type => "stcp",
        role => "visitor",
        sk => &$get_frp_sk($comp),
        server_name => "$comp.$name",
        bind_port => $port,
        bind_addr => "0.0.0.0",
    ]);
};
my $frp_client = sub{
    my($comp,$server)=@_;
    my($name,$port) = &$split_port($server);
    ("$comp.$name" => [
        type => "stcp",
        sk => &$get_frp_sk($comp),
        local_ip => $name,
        local_port => $port,
    ]);
};
my $frp_web = sub{
    my($comp)=@_;
    (
    "$comp.http" => [
        type => "http",
        local_ip => "haproxy",
        local_port => 80,
        subdomain => $comp,
    ],
    "$comp.https" => [
        type => "https",
        local_ip => "haproxy",
        local_port => 443,
        subdomain => $comp,
    ],
    );
};

push @tasks, ["compose_up","fast|full $composes_txt",sub{
    my($mode,$run_comp)=@_;
    sy(&$ssh_add());
    my $conf = &$get_compose($run_comp);
#    my $main_comp = $$conf{main};
    my $ext_conf = &$merge(&$template_yml(),$conf);
#    if($main_comp){
#        my $frpc_conf = [
#            common => [&$get_frp_common($run_comp)],
#            #&$frp_visitor($main_comp,$http_server),
#            &$frp_web($$conf{proxy_dom}||$run_comp),
#        ];
#        &$compose_up($mode,$run_comp,$ext_conf,$frpc_conf,&$get_frp_sk($main_comp));
#    } else {
        my $frpc_conf = [
            common => [&$get_frp_common($run_comp)],
            &$frp_client($run_comp,$bootstrap_server),
            &$frp_web($$conf{proxy_dom}||$run_comp),
        ];
        &$compose_up($mode,$run_comp,$ext_conf,$frpc_conf,&$get_frp_sk($run_comp));
#    }
}];

#snapshots => [
#                type => "stcp",
#                sk => &$get_frp_sk($run_comp),
#                plugin => "static_file",
#                plugin_local_path => "/c4/db4/snapshots",
#                plugin_strip_prefix => "snapshots",
#            ],

#### proxy

#my $mk_from_cfg = sub{
#my($conf)=@_;
#my($ts,$gate_addr,$sni_postfix) = map{$$conf{$_}||die} qw[items gate sni_prefix];
#qq{
#global
#  tune.ssl.default-dh-param 2048
#defaults
#  timeout connect 5s
#  timeout client  3d
#  timeout server  3d
#  mode tcp
#}.join('',map{qq{listen listen_$$_[1]
#  bind $$_[0]
#  server s_$$_[1] $gate_addr ssl verify none sni str("$$_[1].$sni_postfix")
#}}@$ts);
#};
#
#my $mk_from_yml = sub{
#qq{
#services:
#  haproxy:
#    image: "haproxy:1.7"
#    userns_mode: "host"
#    network_mode: "host"
#    restart: unless-stopped
#    volumes:
#    - "./haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"
#version: '3.2'
#};
#};

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
  default_backend beh_frps
backend beh_letsencrypt
  mode http
  server s_letsencrypt $to_ssl_host:8080
backend beh_frps
  mode http
  server s_frps frps:$vhost_http_port
frontend fe443
  bind :443 ssl crt-list /etc/letsencrypt/haproxy/list.txt}.join('',map{qq{
  use_backend be_$$_[0] if { ssl_fc_sni -m dom $$_[0] }}}@$ts).qq{
  default_backend be_frps
backend be_frps
  server s_frps frps:$vhost_https_port ssl verify none
}.join('',map{qq{backend be_$$_[0]
  server s_$$_[0] $$_[1]
}}@$ts).join('',map{ my $port=/(\d+)$/?$1:die; qq{listen listen_$port
  bind :$port
  server s_simple_$port $_
}}@$lis).qq{
};
};

my $mk_to_yml = sub{
my($conf)=@_;
my($to_ssl_host,$li_host,$lis) = map{$$conf{$_}||die} qw[external_ip internal_ip plain_items];
qq{
services:
  haproxy:
    image: "haproxy:1.7"
    restart: unless-stopped
    volumes:
    - "./haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"
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
    - "./frps/frps.ini:/frps.ini:ro"
    ports:
    - "7000:7000"
    - "7500:7500"
version: '3.2'
volumes:
  certs: {}
};
};#$to_ssl_host:

my $hmap = sub{ my($h,$f)=@_; map{&$f($_,$$h{$_})} sort keys %$h };


push @tasks, ["proxy_to","up|test",sub{
    my($mode)=@_;
    my $conf = &$get_deploy_conf()->{proxy_to} || die;
    my $comp = $$conf{stack} || die;
#    my @sb_items = &$hmap($composes, sub{ my($comp,$comp_conf)=@_;
#        $$comp_conf{proxy_dom} ? do{
#            my $host = $$comp_conf{host}||die;
#            my $port = $$comp_conf{http_port}||die;
#            [$$comp_conf{proxy_dom},"$host:$port"]
#        } : ()
#    });
    my $yml_str = &$mk_to_yml($conf);
    my($put,$sync) = &$docker_compose_start($yml_str);
    &$put("haproxy/haproxy.cfg",&$mk_to_cfg($conf,[@{$$conf{items}||[]}])); #@sb_items
    my %common = &$get_frp_common($comp);
    my $tmp_frps_path = &$put("frps/frps.ini",&$to_ini_file([common=>[
        token=>$common{token},
        dashboard_addr => "0.0.0.0",
        dashboard_port => "7500",
        dashboard_user => "cone",
        dashboard_pwd => ($common{token}||die),
        vhost_http_port => $vhost_http_port,
        vhost_https_port => $vhost_https_port,
        subdomain_host => ($$conf{subdomain_host}||die)
    ]]));
    if($mode eq "up"){
        sy(&$ssh_add());
        &$sync($comp);
        sy(&$docker_compose_up($comp," --force-recreate"));
    }
}];

#push @tasks, ["proxy_from"," ",sub{
#    my $conf = $$deploy_conf{proxy_from} || die;
#    my $yml_str = &$mk_from_yml("./from-haproxy.cfg");
#    my($put,$sync) = &$docker_compose_start($yml_str);
#    &$put("haproxy/haproxy.cfg",&$mk_from_cfg($conf));
#    print "docker-compose -p proxy up -d --remove-orphans --force-recreate\n"
#}];

push @tasks, ["cert","<hostname>",sub{
  my($nm)=@_;
  sy(&$ssh_add());
  my $conf = &$get_deploy_conf()->{proxy_to} || die;
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

push @tasks, ["devel_init_frpc"," ",sub{
    my $comp = "devel";
    my $sk = &$get_frp_sk($comp);
    my $proxy_list = (&$get_deploy_conf()->{proxy_to}||die)->{visits}||die;
    my $put = sub{
        my($inner_comp,$fn,$content) = @_;
        &$remote_interactive($comp, " -uc4 $inner_comp\_sshd_1 ", "cat > /c4/$fn")." < ".&$put_temp($fn,$content)
    };
    for my $container(&$running_containers_all($comp)){
        my $inner_comp = $container=~/^(\w+)_sshd_/ ? $1 : next;
        sy(&$put($inner_comp,"frpc.ini",&$to_ini_file([
             common => [&$get_frp_common("devel"), user=>$inner_comp],
             &$frp_web($inner_comp),
             map{my($port,$container)=@$_;("p_$port" => [
                 type => "stcp",
                 sk => $sk,
                 local_ip => $container,
                 local_port => $port,
             ])} @$proxy_list
        ])));
        sy(&$put($inner_comp,"frpc_visitor.ini", &$to_ini_file([
            common => [&$get_frp_common("devel"), user=>$inner_comp],
            map{my($port,$container)=@$_;("p_$port\_visitor" => [
                type => "stcp",
                role => "visitor",
                sk => $sk,
                server_name => "p_$port",
                bind_port => $port,
                bind_addr => "127.0.20.2",
            ])} @$proxy_list
        ])));
        sy(&$remote($comp,"docker restart $inner_comp\_frpc_1"));
    }
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

push @tasks, ["repl","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    sy(&$ssh_ctl($comp,'-t',"docker exec -it $comp\_$service\_1 sh -c 'test -e /c4/.ssh/id_rsa || ssh-keygen;ssh localhost -p22222'"));
}];

####

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[2]->(@args) for @tasks;
} else {
    my $width = 6;
    my $composes = &$get_deploy_conf()->{stacks} || die;
    print join '', map{"$_\n"}
        "stacks:",
        (map{"  $_".(" "x($width-length))." -- ".(($$composes{$_}||die)->{description}||'?')} sort keys %$composes),
        "usage:",
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
}

#userns_mode: "host"
