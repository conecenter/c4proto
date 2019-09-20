#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

my $composes_txt = "<stack>";

my $need_path = sub{
    my($dn)=@_;
    -d $dn or sy("mkdir -p $dn") if $dn=~s{/[^/]*$}{};
    $_[0];
};

my $tmp_root = "/tmp/c4prod";
my $get_tmp_path_inner = sub{ my($fn)=@_; &$need_path("$tmp_root/$$/$fn") };
my $put_temp = sub{
    my($fn,$text)=@_;
    my $path = &$get_tmp_path_inner($fn);
    &$put_text($path,$text);
    print "generated $path\n";
    $path;
};
my $cleanup = sub{
    sy("rm","-rf",$_) for grep{/(\d+)/ and $$ eq $1 ||!-e "/proc/$1"} <$tmp_root/*>;
};
my $get_tmp_path; $get_tmp_path = sub{
    my($c)=@_;
    my $path = &$get_tmp_path_inner("$c");
    (-e $path) ? &$get_tmp_path($c+1) : $path;
};
my $get_tmp_dir = sub{
    my $path = &$get_tmp_path(0);
    &$need_path("$path/");
    $path;
};

my $get_conf_cert_path = lazy{
    my $path = $ENV{C4DEPLOY_CONF} || die "no C4DEPLOY_CONF";
    my $dir = &$get_tmp_dir();
    sy("cd $dir && tar -xzf $path");
    "$dir/id_rsa";
};

my $ssh_add  = sub{ "ssh-add ".&$get_conf_cert_path() };

my $get_conf_dir = lazy{
    my($host,$port,$path) =
        $ENV{C4DEPLOY_LOCATION}=~m{^([\w+\.\-]+):(\d+)(/[\w+\.\-/]+)$} ?
        ($1,$2,$3) : die "bad C4DEPLOY_LOCATION";
    my $remote_loc = "c4\@$host:$path";
    my $rsync = "rsync -e 'ssh -p $port' -a --del";
    my $local_dir = &$get_tmp_dir();
    sy("$rsync $remote_loc/ $local_dir");
    [$local_dir,"$rsync $local_dir/ $remote_loc"];
};
my $get_deploy_conf = lazy{
    my($dir,$save) = @{&$get_conf_dir()};
    require "$dir/main.pl"
};
my $get_compose = sub{&$get_deploy_conf()->{$_[0]}||die "composition expected $_[0]"};

my $get_deployer = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    $$conf{deployer} || $comp;
};
my $get_deployer_conf = sub{
    my($comp,$chk,@k)=@_;
    my $n_conf = &$get_compose(&$get_deployer($comp));
    map{$$n_conf{$_} || $chk && die "no $_"} @k;
};
my $get_host_port = sub{ &$get_deployer_conf($_[0],1,qw(host port)) };

my $ssh_ctl = sub{
    my($comp,@args)=@_;
    my ($host,$port) = &$get_host_port($comp);
    ("ssh","c4\@$host","-p$port",@args);
};
my $remote = sub{
    my($comp,$stm)=@_;
    my ($host,$port) = &$get_host_port($comp);
    "ssh c4\@$host -p $port '$stm'"; #'' must be here; ssh joins with ' ' args for the remote sh anyway
};

my $find_handler = sub{
    my($ev,$comp)=@_;
    my $nm = "$ev-".&$get_compose($comp)->{type};
    my @todo = map{$$_[0] eq $nm ? $$_[2] : ()} @tasks;
    @todo == 1 ? $todo[0] : die "no handler: $nm,$comp";
};

my $find_exec_handler = sub{
    my ($comp) = @_;
    &$find_handler(exec=>&$get_deployer($comp))->($comp);
};

my $interactive = sub{
    my($comp,$container,$stm)=@_;
    my $mk_exec = &$find_exec_handler($comp);
    &$mk_exec("-i",$container,$stm);
};

my $running_containers_all = sub{
    my($comp)=@_;
    my $ps_stm = &$remote($comp,'docker ps --format "table {{.Names}}"');
    my ($names,@ps) = syf($ps_stm)=~/([\w\-]+)/g;
    $names eq "NAMES" or die;
    @ps;
};

my $rsync_to = sub{
    my($from_path,$comp,$to_path)=@_;
    my ($host,$port) = &$get_host_port($comp);
    sy(&$remote($comp,"mkdir -p $to_path"));
    sy("rsync -e 'ssh -p $port' -a --del --no-group $from_path/ c4\@$host:$to_path");
};

my $split_app = sub{
    my($app)=@_;
    $app=~/^([\w\-]+)-(\w+)$/ ? ($1,$2) : die "<stack>-<container> expected ($app)"
};

my $rel_put_text = sub{
    my($base_path) = @_;
    sub{
        my($add_path,$cont)=@_;
        &$put_text(&$need_path("$base_path/$add_path"),$cont);
    };
};

my $sync_up = sub{
    my($comp,$from_path,$up_content,$args)=@_;
    my ($dir) = &$get_deployer_conf($comp,1,qw[dir]);
    my $conf_dir = "$dir/$comp"; #.c4conf
    $from_path ||= &$get_tmp_dir();
    &$rel_put_text($from_path)->("up",$up_content);
    &$rsync_to($from_path,$comp,$conf_dir);
    sy(&$remote($comp,"cat >> $conf_dir.args")." < ".&$put_temp("args"," $args\n")) if $args;
    sy(&$remote($comp,"cd $conf_dir && chmod +x up && ./up"));
};

my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
};

my $http_port = "1080";

####

push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
}];

push @tasks, ["edit","<main|auth> #todo locking or merging",sub{
    my($cf)=@_;
    sy(&$ssh_add());
    my($dir,$save) = @{&$get_conf_dir()};
    sy("mcedit","$dir/$cf.pl");
    sy($save);
}];

push @tasks, ["stack_list"," ",sub{
    sy(&$ssh_add());
    my $width = 6;
    my $composes = &$get_deploy_conf() || die;
    print join '', map{"$_\n"}
        (map{"  $_".(" "x($width-length))." -- ".(($$composes{$_}||die)->{description}||'?')} sort keys %$composes);
}];

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

push @tasks, ["remote_service-gate",'',sub{"zookeeper"}];
push @tasks, ["remote_service-desktop",'',sub{"sshd"}];

my $remote_acc  = sub{
    my($comp,$stm)=@_;
    my $service = &$find_handler(remote_service=>$comp)->();
    my $mk_exec = &$find_exec_handler($comp);
    &$remote($comp,&$mk_exec("",$service,$stm));
};

my $snapshots_path = "/c4db/snapshots";
my $list_snapshots = sub{
    my($comp,$opt)=@_;
    my $ls = &$remote_acc($comp,"ls $opt $snapshots_path");
    print "$ls\n";
    syl($ls);
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
    &$get_sm_binary($comp,"$snapshots_path/$fn",&$mk_path($zfn));
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

my $put_snapshot = sub{
    my($auth_path,$data_path,$addr)=@_;
    my $gen_dir = $ENV{C4PROTO_DIR} || die;
    my $data_fn = $data_path=~m{([^/]+)$} ? $1 : die "bad file path";
    -e $auth_path or die "no gate auth";
    sy("python3","$gen_dir/req.py",$auth_path,$data_path,$addr,"/put-snapshot","/put-snapshot","snapshots/$data_fn");
};

push @tasks, ["put_snapshot", "$composes_txt <file_path>", sub{
    my($comp,$data_path)=@_;
    my $host = &$get_compose($comp)->{le_hostname} || die "no le_hostname";
    my($conf_dir,$save) = @{&$get_conf_dir()};
    my $auth_path = "$conf_dir/ca/$comp/simple.auth";
    &$put_snapshot($auth_path,$data_path,"https://$host");
}];
push @tasks, ["put_snapshot_local", "<file_path>", sub{
    my($data_path)=@_;
    my $data_dir = $ENV{C4DATA_DIR} || die;
    &$put_snapshot("$data_dir/simple.auth",$data_path,"http://127.0.0.1:$http_port");
}];


my $get_kc_ns = sub{
    my($comp)=@_;
    syf(&$remote($comp,'cat /var/run/secrets/kubernetes.io/serviceaccount/namespace'))=~/(\w+)/ ? "$1" : die;
};

my $exec_stm_dc = sub{
    my($md,$comp,$service,$stm)=@_;
    qq[docker exec $md $comp\_$service\_1 sh -c "JAVA_TOOL_OPTIONS= $stm"];
};
my $exec_stm_kc = sub{
    my($ns,$md,$comp,$service,$stm) = @_;
    qq[kubectl -n $ns exec $md $comp-0 -c $service -- sh -c "JAVA_TOOL_OPTIONS= $stm"];
};
my $exec_dc = sub{
    my($comp)=@_;
    sub{ my($md,$service,$stm)=@_; &$exec_stm_dc($md,$comp,$service,$stm) };
};
my $exec_kc = sub{
    my($comp)=@_;
    my $ns = &$get_kc_ns($comp);
    sub{ my($md,$service,$stm)=@_; &$exec_stm_kc($ns,$md,$comp,$service,$stm) };
};
my $lscont_dc = sub{
    my($comp)=@_;
    map{ my $c = $_; sub{ my($stm)=@_; &$exec_stm_dc("",$comp,$c,$stm) } }
    map{ /^(.+)_([a-z]+)_1$/ && $1 eq $comp && $2 ne "pod" ? "$2" : () }
    &$running_containers_all($comp)
};
my $lscont_kc = sub{
    my($comp)=@_;
    my $ns = &$get_kc_ns($comp);
    my $stm = "kubectl -n $ns get po/$comp-0 -o jsonpath={.spec.containers[*].name}";
    map{ my $c = $_; sub{ my($stm)=@_; &$exec_stm_kc($ns,"",$comp,$c,$stm) } }
        syf(&$remote($comp,$stm))=~/(\w+)/g;
};

push @tasks, ["exec-dc_host","",$exec_dc];
push @tasks, ["exec-kc_host","",$exec_kc];
push @tasks, ["lscont-dc_host", "", $lscont_dc];
push @tasks, ["lscont-kc_host", "", $lscont_kc];

push @tasks, ["gc","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    for my $l_exec(&$find_handler(lscont=>&$get_deployer($comp))->($comp)){
        #print "container: $c\n";
        my $cmd = &$remote($comp,&$l_exec("jcmd || echo -"));
        for(syl($cmd)){
            my $pid = /^(\d+)/ ? $1 : next;
            /JCmd/ && next;
            sy(&$remote($comp,&$l_exec("jcmd $pid GC.run")));
        }
    }
}];

#### composer

use YAML::XS qw(LoadFile Load DumpFile Dump);
$YAML::XS::QuoteNumericStrings = 1;

#my $inbox_prefix = '';

my $le_http_port = "2080";
my $le_https_port = "2443";
my $vhost_http_port = "80";
my $vhost_https_port = "443";
my $ssh_port = "2022";
#my $parent_http_server = "f r p c:8067";

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

use List::Util qw(reduce);

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };
my $merge_list = sub{ reduce{ &$merge($a,$b) } @_ };

my $to_yml_str = sub{
    my($generated) = @_;
    #my $yml_str = Dump($generated);
    #DumpFile("docker-compose.yml",$generated);
    my $yml_str = Dump($generated);
    #$text=~s/(\n\s+-\s+)([^\n]*\S:\d\d)/$1"$2"/gs;
    $yml_str=~s/\b(\w+:\s)'(true|false)'/$1$2/g;
    $yml_str
};

my $interpolation_body = sub{
    my($run_comp)=@_;
    'my %vars = `cat ../'.$run_comp.'.args`=~/([\w\-\.\:\/]+)/g;'
    .'print "[$_=$vars{$_}]\n" for sort keys %vars;'
    .'$c{main}=~s{<var:([^>]+)>}{$vars{$1}}eg;'
};
my $pl_head = sub{
    "#!/usr/bin/perl\n"
    .'use strict; use utf8; my %c;'
    .'sub pp{ print "|$_[1]\n" and open OUT, "|$_[1]" and print OUT $c{$_[0]} and close OUT or die };'
};
my $pl_embed = sub{
    my($nm,$v)=@_;
    $v=~/C4_END_TEMPLATE/ && die;
    "\$c{$nm} = <<'C4_END_TEMPLATE';\n$v\nC4_END_TEMPLATE\n";
};

my $make_dc_yml = sub{
    my ($name,$tmp_path,$options) = @_;
    my %all = map{%$_} @$options;
    my @unknown = &$map(\%all,sub{ my($k,$v)=@_;
        $k=~/^([A-Z]|host:|port:|ingress:)/ ||
        $k=~/^(volumes|tty|image|name)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    my @port_maps = &$map(\%all,sub{ my($k,$v)=@_; $k=~/^port:(.+)/ ? "$1" : () });
    my @host_maps = &$map(\%all,sub{ my($k,$v)=@_; $k=~/^host:(.+)/ ? "$1:$v" : () });
    my @pod = (pod => {
        command => ["sleep","infinity"],
        image => "ubuntu:18.04",
        restart=>"unless-stopped",
        @port_maps ? (ports=>\@port_maps) : (),
        @host_maps ? (extra_hosts=>\@host_maps) : (),
    });
    my $yml_str = &$to_yml_str({
        services => {@pod, map{
            my $opt = $_;
            my $res = &$merge_list(&$map($opt,sub{ my($k,$v)=@_;(
                $k=~/^([A-Z].+)/ ? { environment=>{$1=>$v} } : (),
                #$k=~/^host:(.+)/ ? { extra_hosts=>["$1:$v"]} : (),
                $k=~/^(volumes|tty)$/ ? {$1=>$v} : (),
                $k=~/^C4/ && $v=~m{^/c4conf/([\w\.]+)$} ? do{ my $fn=$1; +{
                    volumes=>["./$fn:/c4conf/$fn"],
                    environment=>{"$k\_MD5"=>md5_hex(syf("cat $tmp_path/$fn"))},
                }} : (),
                $k eq 'C4DATA_DIR' ? {volumes=>["db4:$v"]} : (),
            )}));
            (($$opt{name}||die)=>{
                command => ($$opt{name}||die),
                image => ($$opt{image}||die),
                restart=>"unless-stopped",
                logging => {
                    driver => "json-file",
                    options => { "max-size" => "20m", "max-file" => "20" },
                },
                depends_on => ['pod'],
                network_mode => "service:pod",
                %$res,
            });
        } @$options},
        version => "3.2",
        $all{C4DATA_DIR} ? (volumes => { db4 => {} }) : (),
    });
    ($yml_str,'"docker-compose -p '.$name.' -f- up -d --remove-orphans".($ENV{C4FORCE_RECREATE}?" --force-recreate":"")');
};

my $wrap_dc = sub{
    my ($name,$from_path,$options) = @_;
    my($yml_str,$up) = &$make_dc_yml($name,$from_path,$options);
    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str)
        .&$interpolation_body($name)
        .'($vars{replicas}-0) or $c{main}=q[version: "3.2"];'
        .qq[pp(main=>$up);];
    ($name,$from_path,$up_content);
};
push @tasks, ["wrap_deploy-dc_host", "", $wrap_dc];


#todo securityContext/runAsUser

my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

my $make_kc_yml = sub{
    my($name,$tmp_path,$spec,$options) = @_;
    my %all = map{%$_} @$options;
    my @unknown = &$map(\%all,sub{ my($k,$v)=@_;
        $k=~/^([A-Z]|host:|port:|ingress:)/ ||
        $k=~/^(tty|image|name)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    #
    my @host_aliases = do{
        my $ip2aliases = &$merge_list({},&$map(\%all,sub{ my($k,$v)=@_; $k=~/^host:(.+)/ ? {$v=>["$1"]} : () }));
        &$map($ip2aliases, sub{ my($k,$v)=@_; +{ip=>$k, hostnames=>$v} });
    };
    #
    my @secrets = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        my @files = &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^C4/ && $v=~m{^/c4conf/([\w\.]+)$} ? "$1" : ()
        });
        @files ? { name => "$name-$nm", files => \@files } : ();
    } @$options;
    my @secret_volumes = map{
        my $n = &$mandatory_of(name=>$_);
        +{ name => "$n-secret", secret => { secretName => $n } }
    } @secrets;
    my %secret_volumes_by_name = map{($$_{name}=>$_)} @secret_volumes;
    #
    my @containers = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        my @env = &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^([A-Z].+)/ ? {name=>$1,value=>"$v"} : ()
        });
        my $secret_name = "$name-$nm-secret";
        my $data_dir = $$opt{C4DATA_DIR};
        my @volume_mounts = (
            $secret_volumes_by_name{$secret_name} ? { name => $secret_name, mountPath => "/c4conf" } : (),
            $data_dir ? { name => "db4", mountPath => $data_dir } : (),
        );
        +{
            name => $nm, args=>[$nm], image => &$mandatory_of(image=>$opt),
            env=>\@env, volumeMounts=>\@volume_mounts,
            $$opt{tty} ? (tty=>$$opt{tty}) : (),
            securityContext => { allowPrivilegeEscalation => "false" },
        }
    } @$options;
    #
    my @volume_claim_templates = $all{C4DATA_DIR} ? (
        volumeClaimTemplates => [{
            metadata => { name => "db4" },
            spec => {
                accessModes => ["ReadWriteOnce"], # Once Many
                resources => { requests => { storage => "100Gi" } },
            },
        }],
    ) : ();
    my @db4_volumes = $all{C4DATA_DIR} ? {name=>"db4"} : ();
    #
    my $stateful_set_yml = &$to_yml_str({
        apiVersion => "apps/v1",
        kind => "StatefulSet",
        metadata => { name => $name },
        spec => {
            %$spec,
            selector => { matchLabels => { app => $name } },
            serviceName => $name,
            template => {
                metadata => { labels => { app => $name } },
                spec => {
                    containers => \@containers,
                    volumes => [@secret_volumes, @db4_volumes],
                    hostAliases => \@host_aliases,
                    imagePullSecrets => [{ name => "regcred" }],
                    securityContext => {
                        runAsUser => 1979,
                        runAsGroup => 1979,
                        fsGroup => 1979,
                        runAsNonRoot => "true",
                    },
                    $all{is_deployer} ? (serviceAccountName => "deployer") : (),
                },
            },
            @volume_claim_templates,
        },
    });
    #
    my @service_yml = do{
        my @ports = &$map(\%all,sub{ my($k,$v)=@_;
            $k=~/^port:(\d+):(\d+)$/ ? {
                #$all{is_deployer} ? (nodePort => $1-0) : (),
                port => $1-0,
                targetPort => $2-0,
                name => "c4-$2"
            } : ()
        });
        @ports ? &$to_yml_str({
            apiVersion => "v1",
            kind => "Service",
            metadata => { name => $name },
            spec => {
                selector => { app => $name },
                ports => \@ports,
                $all{is_deployer} ? (type => "NodePort") : ()
            },
        }) : ();
    };
    #
    my @ingress_yml = do{
        my @items = &$map(\%all,sub{ my($k,$v)=@_;
            $k=~/^ingress:(.+)$/ ? {host=>$1,port=>$v-0} : ()
        });
        my $disable_tls = 0; #make option when required
        my @annotations = $disable_tls ? () : (annotations=>{
            "certmanager.k8s.io/acme-challenge-type" => "http01",
            "certmanager.k8s.io/cluster-issuer" => "letsencrypt-prod",
            "kubernetes.io/ingress.class" => "nginx",
        });
        my @tls = $disable_tls ? () : (tls=>[{
            hosts => [map{$$_{host}}@items],
            secretName => "$name-tls",
        }]);
        my @rules = map{+{
            host => $$_{host},
            http => {
                paths => [{
                    backend => {
                        serviceName => $name,
                        servicePort => $$_{port},
                    },
                }],
            },
        }} @items;
        @rules ? &$to_yml_str({
            apiVersion => "extensions/v1beta1",
            kind => "Ingress",
            metadata => { name => $name, @annotations },
            spec => { rules => \@rules, @tls },
        }) : ();
    };
    #
    my @secrets_yml = map{ &$to_yml_str({
        apiVersion => "v1",
        kind => "Secret",
        metadata => { name => $$_{name} },
        type => "Opaque",
        data => { map{($_=>syf("base64 -w0 < $tmp_path/$_"))} @{$$_{files}||die} },
    })} @secrets;
    #
    join("", @secrets_yml, @service_yml, @ingress_yml, $stateful_set_yml);
};

my $wrap_kc = sub{
    my($name,$tmp_path,$options) = @_;
    my $yml_str = &$make_kc_yml($name,$tmp_path,{ replicas => "<var:replicas>" },$options);
    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str)
        .&$interpolation_body($name)
        .q[my $ns=`cat /var/run/secrets/kubernetes.io/serviceaccount/namespace`;]
        .q[$ENV{C4FORCE_RECREATE} and system "kubectl -n $ns delete pods/].$name.q[-0" and die $?;]
        .q[pp(main=>"kubectl -n $ns apply -f-");];
    ($name,undef,$up_content);
};
push @tasks, ["wrap_deploy-kc_host", "", $wrap_kc];

#todo apply -n $ns

#networks => { default => { aliases => ["broker","zookeeper"] } },

my $sys_image_ver = "v53";
my $remote_build = sub{
    my($comp,$dir)=@_;
    my($build_comp,$repo) = &$get_deployer_conf($comp,1,qw[builder sys_image_repo]);
    my $conf = &$get_compose($comp);
    my $type = $$conf{type} || die;
    my $tag = "$repo:$type.$sys_image_ver";
    my $build_temp = syf("hostname")=~/(\w+)/ ? "c4build_temp/$1" : die;
    my $nm = $dir=~m{([^/]+)$} ? $1 : die;
    &$rsync_to($dir,$build_comp,"$build_temp/$nm");
    sy(&$remote($build_comp,"docker build -t $tag $build_temp/$nm"));
    sy(&$ssh_ctl($build_comp,"-t","docker push $tag"));
    $tag;
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

my $frp_auth_all = lazy{
    my($dir,$save) = @{&$get_conf_dir()};
    require "$dir/auth.pl"
};
my $get_auth = sub{
    my($comp) = @_;
    @{&$frp_auth_all()->{$comp}||[]};
};

my $split_port = sub{ $_[0]=~/^(\S+):(\d+)$/ ? ($1,$2) : die };

my $get_frp_common = sub{
    my($comp)=@_;
    my ($frps) = &$get_deployer_conf($comp,1,qw[frps]);
    my ($proxy) = &$get_deployer_conf($comp,0,qw[frp_http_proxy]);
    my $deployer_comp = &$get_compose($comp)->{deployer} || $comp;
    my %common_auth = &$get_auth($deployer_comp);
    my $token = $common_auth{frps_token} || die "no frps_token in $deployer_comp";
    my($frps_addr,$frps_port) = &$split_port($frps);
    (
        server_addr => $frps_addr,
        server_port => $frps_port,
        token => $token,
        $proxy ? (http_proxy => $proxy) : (),
    );
};

my $get_frpc_conf = sub{
    my($comp) = @_;
    my $services = &$find_handler(visit=>$comp)->($comp);
    my %auth = &$get_auth($comp);
    my $sk = $auth{"simple.auth"} || die "no simple.auth";
    my @common = (common => [&$get_frp_common($comp)]);
    my @add = map{
        my($service_name,$port,$host) = @$_;
        ("$comp.$service_name" => [
            type => "stcp",
            sk => $sk,
            local_ip => $host || "127.0.0.1",
            local_port => $port,
        ])
    } @$services;
    &$to_ini_file([@common,@add]);
};
my $put_frpc_conf = sub{
    my($from_path,$content) = @_;
    my $put = &$rel_put_text($from_path);
    &$put("frpc.ini", $content);
};

my $make_visitor_conf = sub{
    my($comp,$from_path,$services) = @_;
    my @common = (common => [&$get_frp_common($comp)]);
    my @add = map{
        my($port,$full_service_name) = @$_;
        my $d_comp = $full_service_name=~/^(.*)\.\w+$/ ? $1 : die;
        my %auth = &$get_auth($d_comp);
        my $sk = $auth{"simple.auth"} || die "no simple.auth";
        ("$full_service_name.visitor" => [
            type => "stcp",
            role => "visitor",
            sk => $sk,
            server_name => $full_service_name,
            bind_port => $port,
            bind_addr => "0.0.0.0",
        ])
    } @$services;
    my $put = &$rel_put_text($from_path);
    &$put("frpc.visitor.ini", &$to_ini_file([@common,@add]));
};

#C4INTERNAL_PORTS => "1080,1443",
#my $conf = &$to_ini_file([&$frp_web($$conf{proxy_dom}||$run_comp)]);

my $consumer_options = sub{(
    tty => "true",
    C4MAX_REQUEST_SIZE => "250000000",
    C4INBOX_TOPIC_PREFIX => "",
    C4AUTH_KEY_FILE => "/c4conf/simple.auth", #gate does no symlinks
    C4KEYSTORE_PATH => "/c4conf/main.keystore.jks",
    C4TRUSTSTORE_PATH => "/c4conf/main.truststore.jks",
    JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport ", # -XX:ActiveProcessorCount=36
    C4LOGBACK_XML => "/c4conf/logback.xml",
)};
# todo secure jmx
#            JAVA_TOOL_OPTIONS => join(' ',qw(
#                -Dcom.sun.management.jmxremote.port=9010
#                -Dcom.sun.management.jmxremote.ssl=false
#                -Dcom.sun.management.jmxremote.authenticate=false
#!                -Dcom.sun.management.jmxremote.local.only=false
#                -Dcom.sun.management.jmxremote.rmi.port=9010
#            )),

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

my $need_deploy_cert = sub{
    my($comp,$from_path)=@_;
    my($dir,$save) = @{&$get_conf_dir()};
    my $was_no_ca = &$need_certs("$dir/ca/$comp","main",$from_path,"/c4conf");
    sy($save) if $was_no_ca;
};

my @var_img = (image=>"<var:image>");

my $make_secrets = sub{
    my($comp,$from_path)=@_;
    my %auth = &$get_auth($comp);
    my $put = &$rel_put_text($from_path);
    &$put($_,$auth{$_}) for sort keys %auth;
};

my $gate_ports = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $host = $$conf{host} || $comp;
    my $external_broker_port = $$conf{broker_port} || 1093;
    my $external_http_port = $$conf{http_port} || $http_port;
    ($host,$external_http_port,$external_broker_port);
};
my $get_ingress = sub{
    my($comp,$http_port)=@_;
    my $hostname = &$get_compose($comp)->{le_hostname} || do{
        my ($domain_zone) = &$get_deployer_conf($comp,0,qw[domain_zone]);
        $domain_zone && "$comp.$domain_zone";
    };
    $hostname ? ("ingress:$hostname"=>$http_port) : ();
};

my $wrap_deploy = sub{
    my ($comp,$from_path,$options) = @_;
    return &$find_handler(wrap_deploy=>&$get_deployer($comp))->($comp,$from_path,$options);
};

push @tasks, ["up-client", "", sub{
    my($run_comp,$args)=@_;
    my $conf = &$get_compose($run_comp);
    my $from_path = &$get_tmp_dir();
    &$make_secrets($run_comp,$from_path);
    my $options = [{
        @var_img, name => "main",
        tty => "true", JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport",
        %$conf,
    }];
    &$sync_up(&$wrap_deploy($run_comp,$from_path,$options),$args);
}];

my $need_logback = sub{
    my ($comp,$from_path) = @_;
    my %auth = &$get_auth($comp);
    my $put = &$rel_put_text($from_path);
    &$put($_,$auth{$_}||"") for "logback.xml";
};

my $up_consumer = sub{
    my($run_comp)=@_;
    my $conf = &$get_compose($run_comp);
    my $gate_comp = $$conf{ca} || die "no ca";
    my ($server,$external_http_port,$external_broker_port) = &$gate_ports($gate_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($gate_comp,$from_path);
    &$make_secrets($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    ($run_comp, $from_path, [{
        @var_img, name => "main", &$consumer_options(),
        C4HTTP_SERVER => "http://$server:$external_http_port",
        C4BOOTSTRAP_SERVERS => "$server:$external_broker_port",
        %$conf,
    }]);
};
my $up_gate = sub{
    my($run_comp)=@_;
    my ($server,$external_http_port,$external_broker_port) = &$gate_ports($run_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    ($run_comp, $from_path, [
        {
            @var_img, name => "zookeeper", C4DATA_DIR => "/c4db", #UseContainerSupport?
        },
        {
            @var_img,
            name => "broker", "port:$external_broker_port:$external_broker_port"=>"",
            C4DATA_DIR => "/c4db",
            C4KEYSTORE_PATH => "/c4conf/main.keystore.jks",
            C4TRUSTSTORE_PATH => "/c4conf/main.truststore.jks",
            C4SSL_PROPS => "/c4conf/main.properties",
            C4BOOTSTRAP_EXT_HOST => $server,
            C4BOOTSTRAP_EXT_PORT => $external_broker_port, #UseContainerSupport?
        },
        {
            @var_img,
            &$consumer_options(),
            name => "gate",
            C4DATA_DIR => "/c4db",
            C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.AkkaGatewayApp",
            C4STATE_REFRESH_SECONDS => 1000,
        },
        {
            @var_img, name => "haproxy",
            C4HAPROXY_CONF=>"/c4/haproxy.cfg",
            C4JOINED_HTTP_PORT => $http_port,
            "port:$external_http_port:$http_port"=>"",
            &$get_ingress($run_comp,$external_http_port),
        },
    ]);
};

my $base_image_steps = sub{(
    "FROM ubuntu:18.04",
    "COPY install.pl /",
    "RUN perl install.pl useradd",
)};

my $prod_image_steps = sub{(
    &$base_image_steps(),
    "RUN perl install.pl apt".
    " curl unzip software-properties-common".
    " lsof mc",
    "RUN add-apt-repository -y ppa:vbernat/haproxy-1.8",
    "RUN perl install.pl apt haproxy",
    "RUN perl install.pl curl https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz",
    "RUN perl install.pl curl https://www-eu.apache.org/dist/kafka/2.2.0/kafka_2.12-2.2.0.tgz",
    "RUN perl install.pl curl http://ompc.oss.aliyuncs.com/greys/release/greys-stable-bin.zip",
    "RUN mkdir /c4db && chown c4:c4 /c4db",
)};

#push @tasks, ["local", "$composes_txt", sub{
#    my($comp)=@_;
#    my $conf = &$get_compose($comp);
#    my $img = $$conf{image} || die;
#
#}];

my $dl_frp_url = "https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz";

my $up_desktop = sub{
    my($comp)=@_;
    my $img = do{
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        my $gen_dir = $ENV{C4PROTO_DIR} || die;
        my $conf_cert_path = &$get_conf_cert_path().".pub";
        sy("cp $gen_dir/install.pl $gen_dir/desktop.pl $gen_dir/haproxy.pl $conf_cert_path $from_path/");
        &$put("c4p_alias.sh", join "\n",
            'export PATH=$PATH:/tools/jdk/bin:/tools/sbt/bin:/tools/node/bin:/tools/kafka/bin',
            'export JAVA_HOME=/tools/jdk',
            'export C4DEPLOY_CONF=/c4conf/ssh.tar.gz',
            "export C4DEPLOY_LOCATION=".($ENV{C4DEPLOY_LOCATION}||die),
            'export C4PROTO_DIR=/c4/c4proto',
            'export C4DATA_DIR=/c4db',
            'export JAVA_TOOL_OPTIONS=-XX:-UseContainerSupport',
            "alias prod='ssh-agent perl /c4/c4proto/prod.pl '",
        );
        &$put("Dockerfile", join "\n",
            &$prod_image_steps(),
            "RUN perl install.pl apt".
            " rsync openssh-client dropbear git certbot".
            " xserver-xspice openbox firefox spice-vdagent terminology".
            " libjson-xs-perl libyaml-libyaml-perl libexpect-perl".
            " atop less bash-completion netcat-openbsd locales tmux uuid-runtime".
            " iputils-ping wget nano",
            "RUN perl install.pl curl $dl_frp_url",
            "RUN perl install.pl curl https://nodejs.org/dist/v8.9.1/node-v8.9.1-linux-x64.tar.xz",
            "RUN perl install.pl curl https://piccolo.link/sbt-1.2.8.tgz",
            "RUN rm -r /etc/dropbear && ln -s /c4/dropbear /etc/dropbear ",
            "COPY desktop.pl haproxy.pl id_rsa.pub c4p_alias.sh /",
            "RUN C4DATA_DIR=/c4db perl /desktop.pl fix",
            "USER c4",
            'ENTRYPOINT ["perl","/desktop.pl"]',
        );
        &$remote_build($comp,$from_path);
    };
    my $from_path = &$get_tmp_dir();
    my $cert_path = $ENV{C4DEPLOY_CONF} || die "no C4DEPLOY_CONF";
    sy("cp $cert_path $from_path/ssh.tar.gz");
    &$make_secrets($comp,$from_path);
    my $conf = &$get_compose($comp);
    my $hostname = $$conf{le_hostname};
    my $https_frpc_content = $hostname ? do{
        &$to_ini_file([
            "$comp.le_http" => [
                type => "http",
                local_ip => "127.0.0.1",
                local_port => $le_http_port,
                #subdomain => $comp,
                custom_domains => $hostname,
            ],
            "$comp.le_https" => [
                type => "https",
                local_ip => "127.0.0.1",
                local_port => $le_https_port,
                #subdomain => $comp,
                custom_domains => $hostname,
            ],
        ]);

    } : "";
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp).$https_frpc_content);
    my $put = &$rel_put_text($from_path);
    &$put("authorized_keys" => join "", map{"$_\n"} @{$$conf{authorized_keys}||[]});
    ($comp, $from_path,[
        {
            image => $img, name => "frpc",
            C4FRPC_INI => "/c4conf/frpc.ini",
        },
#        {
#            image => $img, name => "desktop",
#            C4DATA_DIR => "/c4db",
#            C4AUTH_KEY_FILE => "/c4conf/simple.auth",
#        },
        {
            image => $img, name => "sshd",
            C4DATA_DIR => "/c4db",
            C4SSH_PORT => $ssh_port,
            C4DEPLOY_CONF => "/c4conf/ssh.tar.gz",
            C4AUTHORIZED_KEYS => "/c4conf/authorized_keys",
        },
        {
            image => $img, name => "haproxy",
            C4HAPROXY_CONF=>"/c4/haproxy.cfg",
            C4JOINED_HTTP_PORT => $http_port,
            C4DATA_DIR => "/c4db",
            $$conf{enable_ingress} ? (
                "port:$http_port:$http_port"=>"",
                &$get_ingress($comp,$http_port),
            ) : ()
        },
        $hostname ? ({
            image => $img, name => "le_http",
            C4HAPROXY_CONF=>"/c4/le_http.cfg",
            C4JOINED_HTTP_PORT => $le_http_port,
            C4DATA_DIR => "/c4db",
            C4HTTPS => "redirect",
        }, {
            image => $img, name => "le_https",
            C4HAPROXY_CONF=>"/c4/le_https.cfg",
            C4JOINED_HTTP_PORT => $le_https_port,
            C4DATA_DIR => "/c4db",
            C4HTTPS => "https:$hostname",
        }) : (),
    ])
};
my $visit_desktop = sub{
    my($comp)=@_;
    [[desktop=>5900],[ssh=>$ssh_port],[debug=>5005],[http=>$http_port]]
};

push @tasks, ["visit-desktop", "", $visit_desktop];

# m  h g b z -- m-h m-b  h-g g-b b-z l-h l-g l-b l-z
# dc:
# kc:

push @tasks, ["up-desktop", "", sub{
    my($run_comp,$args)=@_;
    &$sync_up(&$wrap_deploy(&$up_desktop($run_comp)),$args);
}];
push @tasks, ["up-consumer", "", sub{
    my($run_comp,$args)=@_;
    &$sync_up(&$wrap_deploy(&$up_consumer($run_comp)),$args);
}];
push @tasks, ["up-gate", "", sub{
    my($run_comp,$args)=@_;
    &$sync_up(&$wrap_deploy(&$up_gate($run_comp)),$args);
}];

# deploy-time, conf-arity, easy-conf, restart-fail-independ -- gate|main|exch (or more, try min);
# dc easy net -- single
# kc safe net max -- broker-zoo|gate|haproxy|main|exch

# zoo: netty, runit, * custom pod

my $cicd_port = "7079";

push @tasks, ["up","$composes_txt <args>",sub{
    sy(&$ssh_add());
    my $up; $up = sub{
        my($comp,$args,@more)=@_;
        &$find_handler(up=>$comp||die)->($comp,$args);
        &$up(@more) if @more;
    };
    &$up(@_);
}];

push @tasks, ["restart","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my ($dir) = &$get_deployer_conf($comp,1,qw[dir]);
    sy(&$remote($comp,"cd $dir/$comp && C4FORCE_RECREATE=1 ./up"));
}];

my $nc = sub{
    my($addr,$exch)=@_;
    my($host,$port) = $addr=~/^([\w\-\.]+):(\d+)$/ ? ($1,$2) : die $addr;
    use IPC::Open2;
    my($chld_out, $chld_in);
    print "nc $host $port\n";
    my $pid = open2($chld_out, $chld_in, 'nc', $host, $port);
    my $req = &$exch($chld_out);
    print $req;
    print $chld_in $req or die;
    print while <$chld_out>;
    waitpid($pid, 0);
    print "FINISHED\n";
};
my $nc_sec = sub{
    my($comp,$addr,$req) = @_;
    my %auth = &$get_auth($comp);
    my $auth = $auth{"deploy.auth"} || die "no deploy.auth";
    &$nc($addr,sub{
        my($chld_out) = @_;
        my $uuid = <$chld_out>;
        my $signature = md5_hex("$auth\n$uuid$req")."\n";
        "$req$signature"
    });

};

push @tasks, ["test_up","",sub{ # <host>:<port> $composes_txt $args
    my($addr,$comp,$args) = @_;
    sy(&$ssh_add());
    my $deployer_comp = &$get_compose($comp)->{deployer} || die;
    my $add = $args ? " $args" : "";
    &$nc_sec($deployer_comp,$addr,"run $comp/up$add\n");
}];
push @tasks, ["test_cd","",sub{ # <host>:<port> $composes_txt <pods|repo>
    my($addr,$comp,$args) = @_;
    sy(&$ssh_add());
    &$nc_sec($comp,$addr,"$args\n");
}];

my $get_head_img_tag = sub{
    my($repo_dir,$parent)=@_;
    #my $repo_name = $repo_dir=~/(\w+)$/ ? $1 : die;
    print "[$repo_dir][$parent]\n";
    my $commit = (!-e $repo_dir) ?
        ($repo_dir=~/^(\w+)$/ ? $1 : die) :
        (syf("git --git-dir=$repo_dir/.git rev-parse --short HEAD")=~/(\w+)/ ? $1 : die);
    #my $commit = syf("git --git-dir=$repo_dir/.git log -n1")=~/\bcommit\s+(\w{10})/ ? $1 : die;
    !$parent ? "base.$commit" :
    $parent=~/^(\w+)$/ ? "base.$1.next.$commit" :
    die $parent;
};

push @tasks, ["ci_build_head","<builder> <req> <dir|commit> [parent]",sub{
    my($builder_comp,$req_pre,$repo_dir,$parent) = @_;
    sy(&$ssh_add());
    my $pf = &$get_head_img_tag($repo_dir,$parent);
    my $req = "build $req_pre.$pf\n";
    my $gen_dir = $ENV{C4PROTO_DIR} || die;
    my ($host,$port) = &$get_host_port($builder_comp);
    my $conf = &$get_compose($builder_comp);
    local $ENV{C4CI_HOST} = $host;
    local $ENV{C4CI_SHORT_REPO_DIRS} = $$conf{C4CI_SHORT_REPO_DIRS} || die "$builder_comp C4CI_SHORT_REPO_DIRS";
    local $ENV{C4CI_ALLOW} = $$conf{C4CI_ALLOW} || die;
    local $ENV{C4CI_CTX_DIR} = $$conf{C4CI_CTX_DIR} || die;
    sy("perl", "$gen_dir/ci.pl", "ci_arg", $req);
}];
push @tasks, ["ci_build_head_tcp","",sub{ # <host>:<port> <req> <dir|commit> [parent]
    my($addr,$req_pre,$repo_dir,$parent) = @_;
    my $pf = &$get_head_img_tag($repo_dir,$parent);
    my $req = "build $req_pre.$pf\n";
    &$nc($addr,sub{ $req });
}];
push @tasks, ["ci_cp_proto","",sub{ #to call from Dockerfile
    my($base,$gen_dir)=@_;
    $base eq 'def' || die "bad tag prefix: $base";
    my $ctx_dir = "/c4/res";
    -e $ctx_dir and sy("rm -r $ctx_dir");
    sy("mkdir $ctx_dir");
    &$put_text("$ctx_dir/.dockerignore",".dockerignore\nDockerfile");
    &$put_text("$ctx_dir/Dockerfile", join "\n",
        &$prod_image_steps(),
        "ENV JAVA_HOME=/tools/jdk",
        'ENV PATH=${PATH}:/tools/jdk/bin:/tools/kafka/bin',
        "COPY . /c4",
        "RUN chown -R c4:c4 /c4",
        "WORKDIR /c4",
        "USER c4",
        "RUN cd /tools/greys && bash ./install-local.sh",
        'ENTRYPOINT ["perl","run.pl"]',
    );
    sy("cp $gen_dir/$_ $ctx_dir/$_") for "install.pl", "run.pl", "haproxy.pl";
    my $server_impl = "c4gate-akka";
    sy("mv $gen_dir/$server_impl/target/universal/stage $ctx_dir/app");
    sy("mv $ctx_dir/app/bin/$server_impl $ctx_dir/app/bin/c4gate");
}];
push @tasks, ["up-ci","",sub{
    my ($comp,$args) = @_;
    my $gen_dir = $ENV{C4PROTO_DIR} || die;
    my $img = do{
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        sy("cp $gen_dir/install.pl $gen_dir/ci.pl $from_path/");
        &$put("Dockerfile", join "\n",
            &$base_image_steps(),
            "RUN perl install.pl apt curl openssh-client socat libdigest-perl-md5-perl",
            "RUN perl install.pl curl $dl_frp_url",
            "COPY ci.pl /",
            "USER c4",
            'ENTRYPOINT ["perl","/ci.pl"]',
        );
        &$remote_build($comp,$from_path);
    };
    my $conf = &$get_compose($comp);
    my ($host,$port) = &$get_host_port($comp);
    #userns_mode => "host" with docker.sock -- bad uid-s
    my @containers = (
        {
            image => $img,
            name => "ci",
            C4CI_KEY_TGZ => "/c4conf/ssh.tar.gz",
            C4CI_PORT => $cicd_port,
            C4CI_HOST => $host,
            tty => "true",
            (map{($_=>$$conf{$_}||die "no $_")} qw[C4CI_SHORT_REPO_DIRS C4CI_ALLOW C4CI_CTX_DIR]),
        },
        {
            image => $img,
            name => "frpc",
            C4FRPC_INI => "/c4conf/frpc.ini",
        },
    );
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    do{
        my $key_dir = &$get_tmp_dir();
        sy(join ' && ',
            "cd $key_dir",
            "ssh-keygen -f id_rsa",
            "ssh-keyscan -H $host > known_hosts",
            "tar -czf $from_path/ssh.tar.gz .",
            "ssh-copy-id -i id_rsa.pub -p $port c4\@$host"
        );
    };
    #sy(&$remote($comp,"mkdir -p $repo_dir"));
    #sy(&$remote($comp,"test -e $repo_dir/.git || git clone $repo_url $repo_dir"));

    my($yml_str,$up) = &$make_dc_yml($comp,$from_path,\@containers);
    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str).qq[pp(main=>$up);];
    &$sync_up($comp,$from_path,$up_content,"");
}];

push @tasks, ["visit-ci", "", sub{
    my($comp)=@_;
    [[main=>$cicd_port]]
}];

my $make_frp_image = sub{
    my ($comp) = @_;
    my $gen_dir = $ENV{C4PROTO_DIR} || die;
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    sy("cp $gen_dir/install.pl $from_path/");
    &$put("frp.pl", join "\n",
        '$ARGV[0] eq "frpc" and exec "/tools/frp/frpc", "-c", $ENV{C4FRPC_INI}||die;',
        '$ARGV[0] eq "frps" and exec "/tools/frp/frps", "-c", $ENV{C4FRPS_INI}||die;',
        'die;'
    );
    &$put("Dockerfile", join "\n",
        &$base_image_steps(),
        "RUN perl install.pl apt curl",
        "RUN perl install.pl curl $dl_frp_url",
        "COPY frp.pl /",
        "USER c4",
        'ENTRYPOINT ["perl", "/frp.pl"]',
    );
    return &$remote_build($comp,$from_path);
};

#my

push @tasks, ["up-frp_client", "", sub{
    my ($comp,$args) = @_;
    my $img = &$make_frp_image($comp);
    my @containers = ({
        image => $img,
        name => "frpc",
        C4FRPC_INI => "/c4conf/frpc.ini",
    });
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

push @tasks, ["visit-frp_client", "", sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $gate_comp = $$conf{ca};
    my @http_client = !$gate_comp ? () : do{
        my ($server,$external_http_port,$external_broker_port) = &$gate_ports($gate_comp);
        ([http=>$external_http_port,$server])
    };
    my @connects = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^frpc:(\w+)$/ ? ["$1",$v=~/^(.+):(\d+)$/?($2,$1):die] : ()
    });
    [@http_client,@connects]
}];

my $get_visitor_conf = sub{
    my ($comp) = @_;
    my $services = &$find_handler(visit=>$comp)->($comp);
    map{ my($name,$port,$host) = @$_; [$port=>"$comp.$name"] } @$services;
};

push @tasks, ["up-visitor", "", sub{
    my ($comp,$args) = @_;
    my $conf = &$get_compose($comp);
    my $server_comp = $$conf{peer} || die;
    my $img = &$make_frp_image($comp);
    my @services = &$get_visitor_conf($server_comp);
    my @ports = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^port:/ ? ($k=>$v) : ()
    });
#    map{
#        my($port,$name) = @$_;
#        my $ext_port = $port == $ssh_port ? 22 : $port;
#        ("port:$ext_port:$port" => "")
#    } @services;
    my @containers = ({
        image => $img,
        name => "frpc",
        C4FRPC_INI => "/c4conf/frpc.visitor.ini",
        @ports,
    });
    my $from_path = &$get_tmp_dir();
    &$make_visitor_conf($comp,$from_path,[@services]);
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

push @tasks, ["up-kc_host", "", sub{
    my ($comp,$args) = @_;
    my $conf = &$get_compose($comp);
    my $gen_dir = $ENV{C4PROTO_DIR} || die;
    my $dir = $$conf{dir} || die;
    my $conf_cert_path = &$get_conf_cert_path().".pub";
    my $img = do{
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        sy("cp $gen_dir/install.pl $gen_dir/cd.pl $conf_cert_path $from_path/");
        &$put("Dockerfile", join "\n",
            &$base_image_steps(),
            "RUN perl install.pl apt curl rsync dropbear uuid-runtime libdigest-perl-md5-perl socat lsof nano",
            "RUN perl install.pl curl $dl_frp_url",
            "RUN rm -r /etc/dropbear && ln -s /c4/dropbear /etc/dropbear ",
            "RUN curl -LO https://storage.googleapis.com/kubernetes-release/release/v1.14.0/bin/linux/amd64/kubectl "
            ."&& chmod +x ./kubectl "
            ."&& mv ./kubectl /usr/bin/kubectl ",
            "RUN mkdir /c4db && chown c4:c4 /c4db",
            "COPY id_rsa.pub cd.pl /",
            "USER c4",
            "RUN mkdir /c4/.ssh /c4/dropbear".
            " && cat /id_rsa.pub > /c4/.ssh/authorized_keys".
            " && chmod 0600 /c4/.ssh/authorized_keys",
            "ENV C4SSH_PORT=$ssh_port",
            'ENTRYPOINT ["perl", "/cd.pl"]',
        );
        &$remote_build($comp,$from_path);
    };
    my @containers = (
        {
            image => $img,
            name => "sshd",
            C4DATA_DIR => "/c4db",
            #$external_ssh_port ? ("port:$external_ssh_port:$ssh_port" => "node") : (),
        },
        {
            image => $img,
            name => "kubectl",
            is_deployer => 1,
        },
        {
            image => $img,
            name => "cd",
            tty => "true",
            C4DATA_DIR => "/c4db",
            C4CD_PORT => $cicd_port,
            C4CD_DIR => $dir,
            C4CD_AUTH_KEY_FILE => "/c4conf/deploy.auth",
            C4CD_REGISTRY => ($$conf{C4CD_REGISTRY}||die "no C4CD_REGISTRY"),
        },
        {
            image => $img,
            name => "frpc",
            C4FRPC_INI => "/c4conf/frpc.ini",
        },
    );
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    &$make_secrets($comp,$from_path);

    my $run_comp = "deployer";
    my $yml_str = &$make_kc_yml($run_comp,$from_path,{},\@containers);
    my $add_yml = join "", map{&$to_yml_str($_)} ({
        apiVersion => "rbac.authorization.k8s.io/v1",
        kind => "Role",
        metadata => { name => $run_comp },
        rules => [
            {
                apiGroups => ["","apps"],
                resources => ["statefulsets","secrets","services"],
                verbs => ["get","create","patch"],
            },
            {
                apiGroups => [""],
                resources => ["pods/exec"],
                verbs => ["create"],
            },
            {
                apiGroups => [""],
                resources => ["pods/log"],
                verbs => ["get"],
            },
            {
                apiGroups => [""],
                resources => ["pods"],
                verbs => ["get","list","delete"],
            },
            {
                apiGroups => ["extensions"],
                resources => ["ingresses"],
                verbs => ["get","create","patch"],
            },
        ],
    }, {
        apiVersion => "v1",
        kind => "ServiceAccount",
        metadata => { name => $run_comp },
    }, {
        apiVersion => "rbac.authorization.k8s.io/v1",
        kind => "RoleBinding",
        metadata => { name => $run_comp },
        subjects => [{ kind => "ServiceAccount", name => $run_comp }],
        roleRef => { kind => "Role", name => $run_comp, apiGroup => "rbac.authorization.k8s.io" },
    });
    print "########\n$add_yml$yml_str";
}];

push @tasks, ["visit-kc_host", "", sub{
    my($comp)=@_;
    [[main=>$cicd_port],[ssh=>$ssh_port]]
}];

###

push @tasks, ["need_certs","",sub{
    &$need_certs(@_);
}];

my $mk_to_cfg = sub{qq{
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
}};

my $mk_to_http = sub{
    my($external_ip)=@_;
    &$mk_to_cfg().join "\n",
        "frontend fe80",
        "  mode http",
        "  bind :80",
        "  acl a_letsencrypt path_beg /.well-known/acme-challenge/",
        "  use_backend beh_letsencrypt if a_letsencrypt",
        "  redirect scheme https if !a_letsencrypt",
        "backend beh_letsencrypt",
        "  mode http",
        "  server s_letsencrypt $external_ip:8080";
};

my $mk_to_https = sub{
    my($ts)=@_;
    &$mk_to_cfg().join "\n",
        "frontend fe443",
        "bind :443 ssl crt-list /etc/letsencrypt/haproxy/list.txt",
        (map{"  use_backend be_$$_[0] if { ssl_fc_sni -m dom $$_[0] }"}@$ts),
        (map{("backend be_$$_[0]", "  server s_$$_[0] $$_[1]")}@$ts);

};

my $mk_to_yml = sub{
my($external_ip)=@_;
qq{
services:
  https:
    image: "haproxy:1.7"
    restart: unless-stopped
    volumes:
    - "./https.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"
    - "certs:/etc/letsencrypt:ro"
    ports:
    - "$external_ip:443:443"
  http:
    image: "haproxy:1.7"
    restart: unless-stopped
    volumes:
    - "./http.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro"
    ports:
    - "$external_ip:80:80"
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
    - "$external_ip:8080:80"
version: '3.2'
volumes:
  certs: {}
};
};#$to_ssl_host:

push @tasks, ["up-proxy","",sub{
    my($comp,$args)=@_;
    my $conf = &$get_compose($comp);
    my $external_ip  = $$conf{external_ip} || die "no external_ip";
    my $yml_str = &$mk_to_yml($external_ip);
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    my @pass = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^pass:(.+)$/ ? [$1=>$v] : ()
    });
    &$put("docker-compose.yml",$yml_str);
    &$put("http.cfg",&$mk_to_http($external_ip));
    &$put("https.cfg",&$mk_to_https(\@pass));
    &$sync_up($comp,$from_path,"#!/bin/bash\ndocker-compose up -d --remove-orphans --force-recreate","");
}];

push @tasks, ["up-frps","",sub{
    my($comp,$args)=@_;
    my $conf = &$get_compose($comp);
    my $ext_ip = $$conf{external_ip} || die "no external_ip";
    my $img = &$make_frp_image($comp);
    my %auth = &$get_auth($comp);
    my $token = $auth{frps_token} || die "no frps_token in $comp";
    my $local_http_port = 1080;
    my $local_https_port = 1443;
    my $conf_content = &$to_ini_file([common=>[
        token=>$token,
        dashboard_addr => "0.0.0.0",
        dashboard_port => "7500",
        dashboard_user => "cone",
        dashboard_pwd => $token,
        vhost_http_port => $local_http_port,
        vhost_https_port => $local_https_port,
    ]]);
    my @containers = ({
        image => $img,
        name => "frps",
        C4FRPS_INI => "/c4conf/frps.ini",
        "port:7000:7000" => "",
        "port:7500:7500" => "",
        "port:$ext_ip:$vhost_https_port:$local_https_port" => "",
        "port:$ext_ip:$vhost_http_port:$local_http_port" => "",
    });
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    &$put("frps.ini", $conf_content);
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

push @tasks, ["cert","$composes_txt <hostname>",sub{
  my($comp,$nm)=@_;
  sy(&$ssh_add());
  my $conf = &$get_compose($comp);
  my $cert_mail = $$conf{cert_mail} || die;
  my $exec = sub{&$remote($comp,"docker exec -i $comp\_certbot_1 sh").' < '.&$put_temp(@_)};
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
  sy(&$remote($comp,"docker exec $comp\_https_1 kill -s HUP 1 || docker restart $comp\_https_1"));
}];

####

my $tp_split = sub{ "$_[0]\n\n"=~/(.*?\n\n)/gs };

my $tp_run = sub{
    my($pkg,$wrap)=@_;
    my $cmd = &$wrap("jcmd");
    my @pid = map{/^(\d+)\s+(\S+)/ && index($2, $pkg)==0?"$1":()} syl($cmd);
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
    my $mk_exec = &$find_exec_handler($comp);
    &$tp_run($pkg,sub{ my($cmd)=@_; &$remote($comp,&$mk_exec("",$service,$cmd)) });#/RUNNABLE/
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
    my $mk_exec = &$find_exec_handler($comp);
    sy(&$ssh_ctl($comp,'-t',&$mk_exec("-it",$service,"test -e /c4/.ssh/id_rsa || ssh-keygen;ssh localhost -p22222")));
}];
push @tasks, ["greys","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    my $mk_exec = &$find_exec_handler($comp);
    sy(&$ssh_ctl($comp,'-t',&$mk_exec("-it",$service,"/tools/greys/greys.sh 1")));
}];

push @tasks, ["install","$composes_txt-<service> <tgz>",sub{
    my($app,$tgz)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    sy(&$remote($comp,&$interactive($comp, $service, "tar -xz"))." < $tgz");
}];
push @tasks, ["cat_visitor_conf","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my @services = &$get_visitor_conf($comp);
    my $from_path = &$get_tmp_dir();
    &$make_visitor_conf($comp,$from_path,[@services]);
    sy("cat $from_path/frpc.visitor.ini");
}];

####

&$main(@ARGV);
&$cleanup();

## todo adapt for kube
#push @tasks, ["logs","$composes_txt-<service>",sub{
#    my($app)=@_;
#    sy(&$ssh_add());
#    my($comp,$service) = &$split_app($app);
#    sy(&$ssh_ctl($comp,'-t',"docker logs $comp\_$service\_1 -tf --tail 1000"));
#}];

#push @tasks, ["add_authorized_key","$composes_txt <key|from>",sub{
#    my($comp,@key)=@_;
#    sy(&$ssh_add());
#    my $content = @key > 1 ? join(' ',@key)."\n" : do{
#        my ($from_comp) = @key;
#        syf(&$remote($from_comp,&$interactive($from_comp,"sshd","cat /c4/.ssh/authorized_keys")));
#    };
#    sy(&$remote($comp,&$interactive($comp,"sshd","cat >> /c4/.ssh/authorized_keys"))." < ".&$put_temp("key",$content));
#}];

#push @tasks, ["devel_init_frpc","<devel>|all",sub{
#    my($developer) = @_;
#    sy(&$ssh_add());
#    my $comp = "devel";
#    my($token,$sk,%auth) = &$get_auth($comp);
#    my $proxy_list = (&$get_deploy_conf()->{proxy_to}||die)->{visits}||die;
#    my $put = sub{
#        my($inner_comp,$fn,$content) = @_;
#        &$remote($comp,&$interactive($inner_comp, "sshd", "cat > /c4/$fn"))." < ".&$put_temp($fn,$content)
#    };
#    my $process = sub{
#        my($inner_comp) = @_;
#        sy(&$put($inner_comp,"frpc.ini",&$to_ini_file([
#             common => [&$get_frp_common("devel")],
#             &$frp_web($inner_comp),
#             map{my($port,$container)=@$_;("$inner_comp.p_$port" => [
#                 type => "stcp",
#                 sk => $sk,
#                 local_ip => $container,
#                 local_port => $port,
#             ])} @$proxy_list
#        ])));
#        sy(&$put($inner_comp,"frpc_visitor.ini", &$to_ini_file([
#            common => [&$get_frp_common("devel")],
#            map{my($port,$container)=@$_;("$inner_comp.p_$port\_visitor" => [
#                type => "stcp",
#                role => "visitor",
#                sk => $sk,
#                server_name => "$inner_comp.p_$port",
#                bind_port => $port,
#                bind_addr => "127.0.20.2",
#            ])} @$proxy_list
#        ])));
#        sy(&$remote($comp,"docker restart $inner_comp\_frpc_1"));
#    };
#    &$process($_) for
#        $developer eq "all" ? (map{/^(\w+)_sshd_/ ? "$1" : ()} &$running_containers_all($comp)) :
#        $developer=~/^(\w+)$/ ? "$1" : die;
#}];

#my $git_with_dir = sub{
#    my($app,$args)=@_;
#    my $conf_dir = $ENV{C4DEPLOY_CONF} || die;
#    my ($git_dir,@git_dirs) = grep{-e} map{"$_/$app.git"} <$conf_dir/*>;
#    $git_dir && !@git_dirs or die "bad git-dir count for $app";
#    my $work_tree = &$get_tmp_path(0);
#    "mkdir $work_tree && git --git-dir=$git_dir --work-tree=$work_tree $args";
#};
#
#my $restart = sub{
#    my($app)=@_;
#    sy(&$git_with_dir($app,"push"));
#    my($comp,$service) = &$split_app($app);
#    my $container = "$comp\_$service\_1";
#    my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
#    sy(&$remote($comp,"docker exec $container kill -3 1"));
#    sy(&$remote($comp,"cd $dir/$comp/$service && git reset --hard && docker restart $container && docker logs $container -ft --tail 2000"));
#};
#
#push @tasks, ["restart","$composes_txt-<service>",sub{
#    my($app)=@_;
#    sy(&$ssh_add());
#    &$restart($app,"");
#}];
#
#push @tasks, ["revert_list","$composes_txt-<service>",sub{
#    my($app)=@_;
#    sy(&$git_with_dir($app,'log --format=format:"%H  %ad  %ar  %an" --date=local --reverse'));
#    print "\n";
#}];
#
#push @tasks, ["revert_to","$composes_txt-<service> <commit>",sub{
#    my($app,$commit)=@_;
#    sy(&$ssh_add());
#    $commit || die "no commit";
#    sy(&$git_with_dir($app,"revert --no-edit $commit..HEAD"));
#    &$restart($app);
#}];
#to: && git checkout $commit -b $commit-$time
#off: && git checkout master


#userns_mode: "host"

#my $sync_from = sub{
#    my($local_path,$comp)=@_;
#    my ($host,$port,$dir) = &$get_host_port(&$get_compose($ca_comp));
#    my $remote_path = "$dir/$comp";
#    sy(&$remote($comp,"mkdir -p $remote_path"));
#    sy("rsync -e 'ssh -p $port' -a --del $user\@$host:$remote_path/ $local_path");
#};
#my $git_info = sub{
#    my($app)=@_;
#    my($comp,$service) = &$split_app($app);
#    my ($host,$port,$ddir) = &$get_host_port(&$get_compose($comp));
#    my $repo = "$ddir/$comp/$service";
#    ($comp,$service,$repo,"ssh://c4\@$host:$port$repo")
#};
#
#my $git_init_remote = sub{
#    my($proj,$app)=@_;
#    my($comp,$service,$repo,$r_repo) = &$git_info($app);
#    #
#    so(&$remote($comp,"mv $repo ".rand()));
#    #
#    my $git = "cd $repo && git ";
#    sy(&$remote($comp,"mkdir -p $repo"));
#    sy(&$remote($comp,"touch $repo/.dummy"));
#    sy(&$remote($comp,"$git init"));
#    sy(&$remote($comp,"$git config receive.denyCurrentBranch ignore"));
#    sy(&$remote($comp,"$git config user.email deploy\@cone.ee"));
#    sy(&$remote($comp,"$git config user.name deploy"));
#    sy(&$remote($comp,"$git add .dummy"));
#    sy(&$remote($comp,"$git commit -am-"));
#};
#
#my $git_init_local = sub{
#    my($proj,$app)=@_;
#    my($comp,$service,$repo,$r_repo) = &$git_info($app);
#    #
#    my $bdir = "$ENV{C4DEPLOY_CONF}/$proj";
#    my $adir = "$bdir/$app.adc";
#    my $git_dir = "$bdir/$app.git";
#    my $tmp = "$bdir/tmp";
#    my $cloned = "$tmp/$service";
#    #
#    sy("mkdir -p $adir $tmp");
#    !-e $_ or rename $_, "$tmp/".rand() or die $_ for $git_dir, $cloned;
#    #
#    &$put_text("$adir/vconf.json",'{}'); #"git.postCommit" : "push"
#    sy("cd $tmp && git clone $r_repo");
#    sy("mv $cloned/.git $git_dir");
#};
#
#push @tasks, ["git_init", "<proj> $composes_txt-<service>", sub{
#    my($proj,$app)=@_;
#    sy(&$ssh_add());
#    &$git_init_remote($proj,$app);
#    &$git_init_local($proj,$app);
#}];
#
#push @tasks, ["git_init_local", "<proj> $composes_txt-<service>", sub{
#    my($proj,$app)=@_;
#    sy(&$ssh_add());
#    &$git_init_local($proj,$app);
#}];


#my $stop = sub{
#    my($comp)=@_;
#    my $acc = &$get_acc($comp);
#    ## stop all but 1
#    for(0){
#        my @ps = grep{$_ ne $acc} &$running_containers($comp);
#        @ps or next;
#        sy(&$remote($comp,"docker stop ".join " ",@ps));
#        sleep 1;
#        redo;
#    }
#};

#my $move_db_to_bak = sub{
#    my($comp)=@_;
#    &$stop($comp);
#    ## move db to bak
#    my $db = "/c4/db4";
#    my $bak = "$db/bak.".time;
#    my $ls_stm = &$remote_acc($comp,"ls $db");
#    my $ls = sub{ grep{!/^bak\./} syf($ls_stm)=~/(\S+)/g };
#    sy(&$remote_acc($comp,"mkdir $bak"));
#    sy(&$remote_acc($comp,"mv $db/$_ $bak/$_")) for &$ls();
#    die $_ for &$ls();
#};

#my $db4put_start = sub{
#    my($mk_path,$from_path) = &$rsync_start();
#    (sub{
#        &$mk_path("f r p c/db4ini/$_[0]");
#    },sub{
#        my($comp)=@_;
#        so(&$remote($comp,sub{"rm -r $_[0]/f r p c/db4ini"}));
#        my ($host,$port,$dir) = &$get_host_port(&$get_compose($comp));
#        &$sync($from_path,$comp,"$dir/$comp"); #! --del
#        sy(&$remote_acc($comp,"rsync -a /c4deploy/db4ini/ /c4/db4"));
#    })
#};
#
#push @tasks, ["put_snapshot", "$composes_txt <file_path>", sub{
#    my($comp,$path)=@_;
#    sy(&$ssh_add());
#    my($mk_path,$sync) = &$db4put_start();
#    my($fn,$zfn) = &$snapshot_name($path=~m{([^/]+)$} ? $1 : die "bad snapshot name");
#    sy("cp $path ".&$mk_path("snapshots/$zfn"));
#    &$move_db_to_bak($comp);
#    &$sync($comp);
#    sy(&$docker_compose_up($comp,""));
#}];

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
#        "C4SNAPSHOTS_URL=http://f r p c:7980/snapshots",
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


#push @tasks, ["stop", $composes_txt, sub{
#    my($comp)=@_;
#    sy(&$ssh_add());
#    &$stop($comp);
#}];
#my $frp_visitor = sub{
#    my($comp,$server)=@_;
#    my($name,$port) = &$split_port($server);
#    ("$comp.$name\_visitor" => [
#        type => "stcp",
#        role => "visitor",
#        sk => &$get_frp_sk($comp),
#        server_name => "$comp.$name",
#        bind_port => $port,
#        bind_addr => "0.0.0.0",
#    ]);
#};
##todo: !$need_commit or `cat $dockerfile`=~/c4commit/ or die "need commit and rebuild";
#push @tasks, ["build_push_zoo","$composes_txt",sub{
#    my($build_comp)=@_;
#    sy(&$ssh_add());
#    my $tag = "cone/c4zoo:u".time;
#    my @dirs = grep{"$_/Dockerfile"} map{<$_/*>} $ENV{C4DOCKERFILE_PATH}=~/[^:]+/g;
#    @dirs==1 or die join ",", @dirs;
#    my ($dir) = @dirs;
#    &$remote_build($build_comp,$dir,$tag);
#    sy(&$ssh_ctl($build_comp,"-t","docker push $tag"));
#}];


#push @tasks, ["build","$composes_txt",sub{
#    my $conf = &$get_compose($run_comp);
#
#    my $build_comp = $$conf{builder} || $run_comp;
#    my $tag = "c4-$run_comp-$img";
#    $is_full and $build_parent_dir and !($was{$img}++)
#        and &$remote_build($build_comp,$build_parent_dir,$img,$tag);
#}];

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


#push @tasks, ["proxy_from"," ",sub{
#    my $conf = $$deploy_conf{proxy_from} || die;
#    my $yml_str = &$mk_from_yml("./from-haproxy.cfg");
#    my($put,$sync) = &$docker_compose_start($yml_str);
#    &$put("haproxy/haproxy.cfg",&$mk_from_cfg($conf));
#    print "docker-compose -p proxy up -d --remove-orphans --force-recreate\n"
#}];