#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

my $sys_image_ver = "v85e";

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

my $ignore = sub{};

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $start = sub{
    print join " ",@_,"\n";
    open my $fh, "|-", @_ or die $!;
    print "opened\n";
    sub{ close $fh or die $! };
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

my $decode = sub{ JSON::XS->new->decode(@_) };
my $encode = sub{
    my($generated) = @_;
    my $yml_str = JSON::XS->new->encode($generated);
    $yml_str=~s/("\w+":\s*)"(true|false)"/$1$2/g;
    $yml_str
};

my $get_kubectl_raw = sub{"kubectl --context $_[0]"};

my $ckh_secret =sub{ $_[0]=~/^([\w\-\.]{3,})$/ ? "$1" : die 'bad secret name' };
my $secret_to_dir = sub{
    my($kubectl,$secret_name,$dir)=@_;
    my $stm = "$kubectl get secret/$secret_name -o json";
    my $data = &$decode(syf($stm))->{data} || die;
    for(sort keys %$data){
        my $v64 = $$data{$_};
        my $fn = &$need_path("$dir/".&$ckh_secret($_));
        sy("base64 -d > $fn < ".&$put_temp("value",$v64));
    }
};

my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

my $ssh_add  = sub{
    so("ssh-add -l") or return;
    my $dir = "$ENV{HOME}/.ssh";
    my $path = "$dir/id_rsa";
    if(!-e $path){
        my $kubectl = &$get_kubectl_raw(&$mandatory_of(C4DEPLOY_CONTEXT=>\%ENV));
        &$secret_to_dir($kubectl,&$mandatory_of(C4DEPLOY_SECRET_NAME=>\%ENV),$dir);
        &$secret_to_dir($kubectl,&$mandatory_of(C4KNOWN_HOSTS_SECRET_NAME=>\%ENV),$dir);
        sy("chmod 0700 $dir && chmod 0600 $dir/*");
    }
    sy("ssh-add $path");
};

my $get_deploy_location = sub{
    my $path = "$ENV{HOME}/.ssh/c4deploy_location";
    syf("cat $path")=~m{^([\w+\.\-]+):(\d+)(/[\w+\.\-/]+)\s*$} ?
        ("$1","$2","$3") : die "bad $path";
};

my $get_conf_dir = lazy{
    my($host,$port,$path) = &$get_deploy_location();
    my $remote_loc = "c4\@$host:$path";
    my $rsync = "rsync -e 'ssh -p $port' -a --del";
    my $local_dir = &$get_tmp_dir();
    sy("$rsync $remote_loc/ $local_dir");
    [$local_dir,"$rsync $local_dir/ $remote_loc"];
};
my $get_deploy_conf = lazy{
    my($dir,$save) = @{&$get_conf_dir()};
    &$ignore($save);
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
my $get_host_port = sub{
    my ($host,$port) = &$get_deployer_conf($_[0],1,qw(host port));
    my ($user) = &$get_deployer_conf($_[0],0,qw(user));
    ($host,$port,$user||"c4")
};

my $ssh_ctl = sub{
    my($comp,@args)=@_;
    my ($host,$port,$user) = &$get_host_port($comp);
    ("ssh","$user\@$host","-p$port",@args);
};
my $remote = sub{
    my($comp,$stm)=@_;
    my ($host,$port,$user) = &$get_host_port($comp);
    "ssh $user\@$host -p $port '$stm'"; #'' must be here; ssh joins with ' ' args for the remote sh anyway
};

my $single_or_undef = sub{ @_==1 ? $_[0] : undef };

my $find_handler = sub{
    my($ev,$comp)=@_;
    my $nm = "$ev-".&$get_compose($comp)->{type};
    &$single_or_undef(map{$$_[0] eq $nm ? $$_[2] : ()} @tasks) || die "no handler: $nm,$comp";
};

my $rsync_to = sub{
    my($from_path,$comp,$to_path)=@_;
    my ($host,$port,$user) = &$get_host_port($comp);
    sy(&$remote($comp,"mkdir -p $to_path"));
    sy("rsync -e 'ssh -p $port' -a --del --no-group $from_path/ $user\@$host:$to_path");
};

my $rel_put_text = sub{
    my($base_path) = @_;
    sub{
        my($add_path,$cont)=@_;
        &$put_text(&$need_path("$base_path/$add_path"),$cont);
    };
};

my $sync_up = sub{
    my($comp,$from_path,$up_content)=@_;
    my ($dir) = &$get_deployer_conf($comp,1,qw[dir]);
    my $conf_dir = "$dir/$comp"; #.c4conf
    $from_path ||= &$get_tmp_dir();
    &$rel_put_text($from_path)->("up",$up_content);
    &$rsync_to($from_path,$comp,$conf_dir);
    sy(&$remote($comp,"cd $conf_dir && chmod +x up && ./up"));
};

my $get_proto_dir = sub{ &$mandatory_of(C4CI_PROTO_DIR=>\%ENV) };

my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
};

####

push @tasks, ["","",sub{
    print "usage:\n", join '', sort map{"$_\n"}
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
}];

push @tasks, ["edit_auth"," ",sub{ #?todo locking or merging
    &$ssh_add();
    my($dir,$save) = @{&$get_conf_dir()};
    sy("mcedit","$dir/auth.pl");
    sy($save);
}];

push @tasks, ["agent"," ",sub{
    my(@args)=@_;
    &$ssh_add();

    sy(@args);
}];

push @tasks, ["ssh", "$composes_txt [command-with-args]", sub{
    my($comp,@args)=@_;
    &$ssh_add();
    sy(&$ssh_ctl($comp,@args));
}];

push @tasks, ["ssh_pipe", "$composes_txt <from_remote_cmd> <to_local_cmd>", sub{
    my($comp,$from_remote_cmd,$to_local_cmd)=@_;
    &$ssh_add();
    #my $fn = $path=~m{([^/]+)$} ? $1 : die;
    sy("cat ".&$put_temp(from=>$from_remote_cmd)." | ".&$remote($comp,"sh")." | sh ".&$put_temp(to=>$to_local_cmd));
}];

my $get_hostname = sub{
    my($comp)=@_;
    &$get_compose($comp)->{le_hostname} || do{
        my ($domain_zone) = &$get_deployer_conf($comp,0,qw[domain_zone]);
        $domain_zone && "$comp.$domain_zone";
    };
};

my $get_kubectl = sub{
    my($comp)=@_;
    my ($context) = &$get_deployer_conf($comp,1,qw[context]);
    &$get_kubectl_raw($context);
};
my $get_pods = sub{
    my($comp)=@_;
    my $kubectl = &$get_kubectl($comp);
    my $stm = qq[$kubectl get po -l app=$comp -o jsonpath="{.items[*].metadata.name}"];
    syf($stm)=~/(\S+)/g;
};
my $get_comp_pods = sub{
    my($arg)=@_;
    $_[0]=~/^(.+)-\d$/ ? ("$1",$arg) :
        $_[0]=~/^(.+)-\w+-\w+$/ ? ("$1",$arg) :
            ($arg,&$get_pods($arg))
};
my $for_comp_pod = sub{
    my($arg,$f)=@_;
    my ($comp,@pods) = &$get_comp_pods($arg);
    if(@pods==0){ print "no pods found\n" }
    elsif(@pods==1){ &$f($comp,@pods) }
    else{ print "multiple pods found: ".join(" ",@pods)."\n" }
};

my $kj_exec = sub{
    my($comp,$pod,$md,$stm)=@_;
    my $kubectl = &$get_kubectl($comp);
    qq[$kubectl exec $md $pod -- sh -c "JAVA_TOOL_OPTIONS= $stm"];
};

push @tasks, ["pods_gc","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    for my $pod(&$get_pods($comp)){
        my $cmd = &$kj_exec($comp,$pod,"","jcmd || echo -");
        for(syl($cmd)){
            my $pid = /^(\d+)/ ? $1 : next;
            /JCmd/ && next;
            sy(&$kj_exec($comp,$pod,"","jcmd $pid GC.run"));
        }
    }
}];

push @tasks, ["pods_del","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    my $kubectl = &$get_kubectl($comp);
    my $pods = join " ", &$get_pods($comp);
    $pods and sy("$kubectl delete pods $pods");
}];

#### composer

my $vhost_http_port = "80";
my $vhost_https_port = "443";

my %merge;
my $merge = sub{&{$merge{join "-",map{ref}@_}||sub{$_[$#_]}}};
$merge{"HASH-HASH"} = sub{
    my($p,$o)=@_;
    +{map{
        my $k = $_;
        ($k=>&$merge(map{(exists $$_{$k})?$$_{$k}:()} $p,$o));
    } keys %{+{%$p,%$o}}};
};
$merge{"ARRAY-ARRAY"} = sub{[map{@$_}@_]};

use List::Util qw(reduce);

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };
my $merge_list = sub{ reduce{ &$merge($a,$b) } @_ };
my $single = sub{ @_==1 ? $_[0] : die };

my $md5_hex = sub{ md5_hex(@_) };

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
    my ($name,$tmp_path,$opt) = @_;
    my @unknown = &$map($opt,sub{ my($k)=@_;
        $k=~/^([A-Z]|port:)/ ||
        $k=~/^(volumes|tty|image)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    my $nm = "main";
    my $res = &$merge_list(&$map($opt,sub{ my($k,$v)=@_;(
        $k=~/^([A-Z].+)/ ? { environment=>{$1=>$v} } : (),
        $k=~/^(volumes|tty)$/ ? {$1=>$v} : (),
        $k=~/^C4/ && $v=~m{^/c4conf/([\w\.]+)$} ? do{ my $fn=$1; +{
            volumes=>["./$fn:/c4conf/$fn"],
            environment=>{"$k\_MD5"=>&$md5_hex(syf("cat $tmp_path/$fn"))},
        }} : (),
        $k=~/^port:(.+)/ ? {ports=>["$1"]} : (),
    )}));
    my $yml_str = &$encode({
        services => {
            $nm=>{
                command => $nm,
                image => ($$opt{image}||die),
                restart=>"unless-stopped",
                logging => {
                    driver => "json-file",
                    options => { "max-size" => "20m", "max-file" => "20" },
                },
                %$res,
            }
        },
        version => "3.2",
    });
    ($yml_str,'"docker-compose -p '.$name.' -f- up -d --remove-orphans".($ENV{C4FORCE_RECREATE}?" --force-recreate":"")');
};

my $wrap_dc = sub{
    my ($name,$from_path,$options) = @_;
    my($yml_str,$up) = &$make_dc_yml($name,$from_path,$options);
    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str).qq[pp(main=>$up);];
    &$sync_up($name,$from_path,$up_content);
};
push @tasks, ["wrap_deploy-dc_host", "", $wrap_dc];

#todo: affinity for headless, replicas 3
#todo securityContext/runAsUser auto?

my $make_kc_yml = sub{
    my($name,$tmp_path,$opt) = @_;
    my @unknown = &$map($opt,sub{ my($k)=@_;
        $k=~/^([A-Z]|host:|port:|ingress:|path:)/ ||
        $k=~/^(tty|image|noderole)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    my $nm = "main";
    #
    my @host_aliases = do{
        my $ip2aliases = &$merge_list({},&$map($opt,sub{ my($k,$v)=@_; $k=~/^host:(.+)/ ? {$v=>["$1"]} : () }));
        &$map($ip2aliases, sub{ my($k,$v)=@_; +{ip=>$k, hostnames=>$v} });
    };
    #
    my @int_secrets = do{
        my @files = &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^C4/ && $v=~m{^/c4conf/([\w\.]+)$} ? "$1" : ()
        });
        @files ? ({ secret => "$name-$nm", path=>"/c4conf", files => \@files }) : ();
    };
    my @ext_secrets = &$map($opt,sub{ my($k,$v)=@_;
        $k=~/^C4/ && "$v/"=~m{^(/c4conf-([\w\-]+))/} ? {secret=>"$2",path=>"$1"} : ()
    });

    my @all_secrets = (@int_secrets,@ext_secrets);
    my @secret_volumes = &$map(
        +{map{(&$mandatory_of(secret=>$_)=>1)} @all_secrets},
        sub{ my($secret)=@_;
            +{ name => "$secret-secret", secret => { secretName => $secret } }
        }
    );
    my @secret_mounts = &$map(
        &$merge_list({},map{
            +{&$mandatory_of(path=>$_)=>{&$mandatory_of(secret=>$_)=>1}}
        } @all_secrets),
        sub{ my($path,$v)=@_;
            my $secret = &$single(keys %$v);
            +{ name => "$secret-secret", mountPath => $path }
        }
    );
    #
    my %affinity = !$$opt{noderole} ? () : (affinity=>{ nodeAffinity=>{
        preferredDuringSchedulingIgnoredDuringExecution=> [{
            weight=> 1,
            preference=> { matchExpressions=> [
                { key=> "noderole", operator=> "In", values=> [$$opt{noderole}] }
            ]},
        }]
    }});
    #
    my %host_path_to_name = &$map($opt,sub{ my($k,$v)=@_;
        $k=~m{^path:} ? ($v=>"host-vol-".&$md5_hex($v)) : ()
    });
    my @host_volumes = &$map(\%host_path_to_name, sub{ my($k,$v)=@_;
        +{ name=>$v, hostPath=>{ path=>$k } }
    });
    my @host_mounts = &$map($opt,sub{ my($k,$v)=@_;
        my $path = $k=~m{^path:(.*)$} ? $1 : undef;
        $path ? { mountPath=>$path, name=> &$mandatory_of($v=>\%host_path_to_name) } : ();
    });

    #

    my @env = &$map($opt,sub{ my($k,$v)=@_;
        $k=~/^([A-Z].+)/ ? {name=>$1,value=>"$v"} : ()
    });

    my $container = {
            name => $nm, args=>[$nm], image => &$mandatory_of(image=>$opt),
            env=>[@env],
            volumeMounts=>[@secret_mounts,@host_mounts],
            $$opt{tty} ? (tty=>$$opt{tty}) : (),
            securityContext => { allowPrivilegeEscalation => "false" },
            resources => {
                limits => {
                    cpu => "64",
                    memory => "64Gi",
                },
                requests => {
                    cpu => &$mandatory_of(req_cpu=>$opt),
                    memory => &$mandatory_of(req_mem=>$opt),
                },
            },
            $$opt{C4READINESS_PATH} ? (
                readinessProbe => {
                    periodSeconds => 3,
                    exec => { command => ["cat",$$opt{C4READINESS_PATH}] },
                },
            ):(),
    };
    #
    my $spec = {
            $$opt{replicas} ? (replicas=>$$opt{replicas}) : (),
            selector => { matchLabels => { app => $name } },
            template => {
                metadata => {
                    labels => { app => $name },
                },
                spec => {
                    containers => [$container],
                    volumes => [@secret_volumes, @host_volumes],
                    hostAliases => \@host_aliases,
                    imagePullSecrets => [{ name => "regcred" }],
                    securityContext => {
                        runAsUser => 1979,
                        runAsGroup => 1979,
                        fsGroup => 1979,
                        runAsNonRoot => "true",
                    },
                    $$opt{is_deployer} ? (serviceAccountName => "deployer") : (),
                    %affinity,
                },
            },
    };
    my $stateful_set_yml = !$$opt{headless} ? {
            apiVersion => "apps/v1",
            kind => "Deployment",
            metadata => { name => $name },
            spec => $spec,
    } : {
            apiVersion => "apps/v1",
            kind => "StatefulSet",
            metadata => { name => $name },
            spec => {
                %$spec,
                serviceName => $name,
            },
    };
    #
    my @service_yml = do{
        my @ports = &$map($opt,sub{ my($k)=@_;
            $k=~/^port:(\d+):(\d+)$/ ? {
                #$all{is_deployer} ? (nodePort => $1-0) : (),
                port => $1-0,
                targetPort => $2-0,
                name => "c4-$2"
            } : ()
        });
        @ports ? {
            apiVersion => "v1",
            kind => "Service",
            metadata => { name => $name },
            spec => {
                selector => { app => $name },
                ports => \@ports,
                $$opt{headless} ? (clusterIP=>"None") : (),
            },
        } : ();
    };
    #
    my @ingress_yml = do{
        my $by_host = &$merge_list({},&$map($opt,sub{ my($k,$v)=@_;
            $k=~m{^ingress:([^/]+)(.*)$} ? {$1=>[{path=>$2,port=>$v-0}]} : ()
        }));
        my @hosts = &$map($by_host,sub{ my($host)=@_; $host });
        my $disable_tls = 0; #make option when required
        my $ingress_secret_name = $$opt{ingress_secret_name};
        my @annotations = $disable_tls ? () : (annotations=>{
            $ingress_secret_name ? () : ("cert-manager.io/cluster-issuer" => "letsencrypt-prod"),
            "kubernetes.io/ingress.class" => "nginx",
        });
        my @tls = $disable_tls ? () : (tls=>[{
            hosts => \@hosts,
            secretName => $ingress_secret_name || "$name-tls",
        }]);
        my @rules = &$map($by_host,sub{ my($host,$v)=@_; +{
            host => $host,
            http => {
                paths => [map{+{
                    backend => {
                        serviceName => $name,
                        servicePort => $$_{port},
                    },
                    $$_{path} ? (path=>$$_{path}) : (),
                }}@$v],
            },
        }});
        @rules ? {
            apiVersion => "extensions/v1beta1",
            kind => "Ingress",
            metadata => { name => $name, @annotations },
            spec => { rules => \@rules, @tls },
        } : ();
    };
    #
    my @secrets_yml = map{+{
        apiVersion => "v1",
        kind => "Secret",
        metadata => { name => &$mandatory_of(secret=>$_) },
        type => "Opaque",
        data => { map{($_=>syf("base64 -w0 < $tmp_path/$_"))} @{$$_{files}||die} },
    }} @int_secrets;
    #
    [@secrets_yml, @service_yml, @ingress_yml, $stateful_set_yml];
};

my $wrap_kc = sub{
    my($name,$tmp_path,$options) = @_;
    my $yml = &$make_kc_yml($name,$tmp_path,$options);
    my $yml_str = join "\n", map{&$encode($_)} @$yml;
    my $kubectl = &$get_kubectl($name);
    sy("$kubectl apply -f ".&$put_temp("up.yml",$yml_str));
};
push @tasks, ["wrap_deploy-kc_host", "", $wrap_kc];

my $remote_build = sub{
    my($type_def,$comp,$dir)=@_;
    my($build_comp,$repo) = &$get_deployer_conf($comp,1,qw[builder sys_image_repo]);
    my $type = $type_def || &$get_compose($comp)->{type} || die;
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
    &$ignore($save);
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
    return "" if !@$services;
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

my $all_consumer_options = sub{(
    tty => "true",
    C4MAX_REQUEST_SIZE => "250000000",
    JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport ", # -XX:ActiveProcessorCount=36
    C4LOGBACK_XML => "/c4conf/logback.xml",
    C4AUTH_KEY_FILE => "/c4conf/simple.auth", #gate does no symlinks
)};

my $need_deploy_cert = sub{
    my($comp,$from_path)=@_;
    my %auth = &$get_auth($comp);
    #print "comp $comp; [".join(',',%auth)."]\n";
    my $put = &$rel_put_text($from_path);
    &$put($_,&$mandatory_of($_=>\%auth)) for "simple.auth";
};

my @req_small = (req_mem=>"100Mi",req_cpu=>"250m");
my @req_big = (req_mem=>"10Gi",req_cpu=>"1000m");
my $env_img = sub{(image=>$ENV{C4IMAGE})};

my $make_secrets = sub{
    my($comp,$from_path)=@_;
    my %auth = &$get_auth($comp);
    my $put = &$rel_put_text($from_path);
    &$put($_,$auth{$_}) for sort keys %auth;
};

my $inner_http_port = 8067;
my $inner_sse_port = 8068;
my $elector_port = 1080;

my $wrap_deploy = sub{
    my ($comp,$from_path,$options) = @_;
    return &$find_handler(wrap_deploy=>&$get_deployer($comp))->($comp,$from_path,$options);
};

my $up_client = sub{
    my($run_comp)=@_;
    my $conf = &$get_compose($run_comp);
    my $from_path = &$get_tmp_dir();
    &$make_secrets($run_comp,$from_path);
    ($from_path, {
        &$env_img(),
        tty => "true", JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport",
        @req_small, %$conf,
    });
};

my $need_logback = sub{
    my ($comp,$from_path) = @_;
    my %auth = &$get_auth($comp);
    my $put = &$rel_put_text($from_path);
    &$put($_,$auth{$_}||"") for "logback.xml";
};

my $get_consumer_options = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $prefix = $$conf{C4INBOX_TOPIC_PREFIX};
    my ($bootstrap_servers,$elector) = &$get_deployer_conf($comp,1,qw[bootstrap_servers elector]);
    (
        &$env_img(),
        &$all_consumer_options(),
        C4INBOX_TOPIC_PREFIX => ($prefix || die "no C4INBOX_TOPIC_PREFIX"),
        C4STORE_PASS_PATH    => "/c4conf-kafka-auth/kafka.store.auth",
        C4KEYSTORE_PATH      => "/c4conf-kafka-certs/kafka.keystore.jks",
        C4TRUSTSTORE_PATH    => "/c4conf-kafka-certs/kafka.truststore.jks",
        C4BOOTSTRAP_SERVERS  => ($bootstrap_servers || die "no host bootstrap_servers"),
        C4HTTP_SERVER        => "http://$comp:$inner_http_port",
        C4ELECTOR_SERVERS    => join(",", map {"http://$elector-$_.$elector:$elector_port"} 0, 1, 2),
        C4READINESS_PATH     => "/c4/c4is-ready",
    )
};

my $up_consumer = sub{
    my($run_comp)=@_;
    my $conf = &$get_compose($run_comp);
    my $gate_comp = $$conf{ca} || die "no ca";
    my %consumer_options = &$get_consumer_options($gate_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($gate_comp,$from_path);
    &$make_secrets($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    &$put_frpc_conf($from_path,&$get_frpc_conf($run_comp));
    ($from_path, {
        %consumer_options, @req_big, C4FRPC_INI => "/c4conf/frpc.ini", %$conf,
    });
};
my $up_gate = sub{
    my($run_comp)=@_;
    my %consumer_options = &$get_consumer_options($run_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    my $hostname = &$get_hostname($run_comp) || die "no le_hostname";
    my ($ingress_secret_name) = &$get_deployer_conf($run_comp,0,qw[ingress_secret_name]);
    ($from_path, {
        %consumer_options,
        C4S3_CONF_DIR => "/c4conf-ceph-client",
        C4STATE_TOPIC_PREFIX => "gate",
        C4STATE_REFRESH_SECONDS => 1000,
        req_mem => "4Gi", req_cpu => "1000m",
        "port:$inner_http_port:$inner_http_port"=>"",
        "port:$inner_sse_port:$inner_sse_port"=>"",
        "ingress:$hostname/"=>$inner_http_port,
        "ingress:$hostname/sse"=>$inner_sse_port,
        ingress_secret_name=>$ingress_secret_name,
        C4HTTP_PORT => $inner_http_port,
        C4SSE_PORT => $inner_sse_port,
    });
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
    " lsof mc iputils-ping netcat-openbsd fontconfig",
    "RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz",
    "RUN perl install.pl curl http://ompc.oss.aliyuncs.com/greys/release/greys-stable-bin.zip",
    'ENV PATH=${PATH}:/tools/jdk/bin',
)};

my $dl_frp_url = "https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz";
my $dl_node_url = "https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz";

# m  h g b z -- m-h m-b  h-g g-b b-z l-h l-g l-b l-z
# dc:
# kc:

push @tasks, ["ci_up-consumer", "", $up_consumer];
push @tasks, ["ci_up-gate", "", $up_gate];
push @tasks, ["ci_up-client", "", $up_client];

# deploy-time, conf-arity, easy-conf, restart-fail-independ -- gate|main|exch (or more, try min);
# dc easy net -- single
# kc safe net max -- broker-zoo|gate|haproxy|main|exch

# zoo: netty, runit, * custom pod

push @tasks, ["up","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    &$find_handler(up=>$comp||die)->($comp);
}];

### snapshot op-s

my $snapshot_name = sub{
    my($snnm)=@_;
    my @fn = $snnm=~/^(\w{16})(-\w{8}-\w{4}-\w{4}-\w{4}-\w{12}[-\w]*)$/ ? ($1,$2) : return;
    my $zero = '0' x length $fn[0];
    ["$fn[0]$fn[1]","$zero$fn[1]"]
};
push @tasks, ["snapshot_get", "$composes_txt [|snapshot|last]", sub{
    my($gate_comp,$arg)=@_;
    &$ssh_add();
    my $prefix = &$get_compose($gate_comp)->{C4INBOX_TOPIC_PREFIX}
        || die "no C4INBOX_TOPIC_PREFIX for $gate_comp";
    my ($client_comp) = &$get_deployer_conf($gate_comp,1,qw[s3client]);
    my ($pod) = &$get_pods($client_comp); # any is ok
    my $stm = sub{
        my($op,$fn) = @_;
        &$kj_exec($client_comp,$pod,"","/tools/mc $op def/$prefix.snapshots/$fn")
    };
    if(!defined $arg){
        sy(&$stm("ls",""));
    } else {
        my $snnm = $arg ne "last" ? $arg :
            (sort{$b cmp $a} grep{ &$snapshot_name($_) } syf(&$stm("ls",""))=~/(\S+)/g)[0];
        my $fn = &$snapshot_name($snnm) || die "bad or no snapshot name";
        sy(&$stm("cat",$$fn[0])." > $$fn[1]");
    }
}];

my $put_snapshot = sub{
    my($auth_path,$data_path,$addr)=@_;
    my $gen_dir = &$get_proto_dir();
    my $data_fn = $data_path=~m{([^/]+)$} ? $1 : die "bad file path";
    -e $auth_path or die "no gate auth";
    sy("python3","$gen_dir/req.py",$auth_path,$data_path,$addr,"/put-snapshot","/put-snapshot","snapshots/$data_fn");
};

my $need_auth_path = sub{
    my($comp)=@_;
    #my %consumer_options = &$get_consumer_options($comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($comp,$from_path);
    "$from_path/simple.auth"
};

push @tasks, ["snapshot_put", "$composes_txt <file_path> [to_address]", sub{
    my($comp,$data_path,$address_arg)=@_;
    &$ssh_add();
    my $host = &$get_hostname($comp);
    my $address = $address_arg || $host && "https://$host" ||
        die "need le_hostname or domain_zone for $comp or address";
    &$put_snapshot(&$need_auth_path($comp),$data_path,$address);
}];

###

push @tasks, ["exec_bash","<pod|$composes_txt>",sub{
    my($arg)=@_;
    &$ssh_add();
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        sy(qq[$kubectl exec -it $pod -- bash]);
    });
}];
push @tasks, ["watch","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    my $kubectl = &$get_kubectl($comp);
    sy(qq[watch $kubectl get po -l app=$comp]);
}];
push @tasks, ["log","<pod|$composes_txt> [tail] [add]",sub{
    my($arg,$tail,$add)=@_;
    &$ssh_add();
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        my $tail_or = ($tail+0) || 100;
        sy(qq[$kubectl log -f $pod --tail $tail_or $add]);
    });
}];

#################

push @tasks, ["builder_cleanup"," ",sub{
    my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
    my @ssh = &$ssh_ctl($builder_comp);
    my @to_kill = map{/^c4\s+(\d+).+\bdocker\s+build\b/?"$1":()} syl(join" ",@ssh,"ps","-ef");
    @to_kill and sy(@ssh,"kill",@to_kill);
    sy(@ssh,"test -e $tmp_root && rm -r $tmp_root; true");
}];


my $gitlab_docker_build = sub{
    my($local_dir,$builder_comp,$img) = @_;
    my $remote_dir = syf("uuidgen")=~/(\S+)/ ? "$tmp_root/$1" : die;
    my $ctx_dir = "$remote_dir/context";
    my $local_config_dir = &$get_tmp_dir();
    &$put_text("$local_config_dir/config.json",&$encode({auths=>{&$mandatory_of(CI_REGISTRY=>\%ENV)=>{
        username=>&$mandatory_of(CI_REGISTRY_USER=>\%ENV),
        password=>&$mandatory_of(CI_REGISTRY_PASSWORD=>\%ENV),
    }}}));
    &$rsync_to($local_config_dir,$builder_comp,"$remote_dir/config");
    &$rsync_to($local_dir,$builder_comp,$ctx_dir);
    my @config_args = ("--config"=>"$remote_dir/config");
    sy(&$ssh_ctl($builder_comp,"-t","docker",@config_args,"build","-t",$img,$ctx_dir));
    sy(&$ssh_ctl($builder_comp,"-t","docker",@config_args,"push",$img)); #todo other repos
    sy(&$ssh_ctl($builder_comp,"rm","-r",$remote_dir));
};

push @tasks, ["gitlab_build_common","",sub{
    my($img)=@_;
    &$ssh_add();
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR=>\%ENV);
    my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
    my $deploy_conf_server_url = &$mandatory_of(C4DEPLOY_CONF_URL=>\%ENV);
    sy("cp $local_dir/build.base.dockerfile $local_dir/Dockerfile");
    my $uuid = syf("uuidgen")=~/(\S+)/ ? $1 : die;
    &$put_text("$local_dir/form.auth","$deploy_conf_server_url/tmp/$uuid");
    &$gitlab_docker_build($local_dir,$builder_comp,$img);
}];


my $gitlab_get_form = sub{
    my($instance,$conf_str,$client_code)=@_;
    my $conf_lines = &$decode($conf_str);
    my @proj_tags = sort map{ref($_) && $$_[0] eq "C4TAG" ? $$_[1] : ()} @$conf_lines;
    my @comp_proj = &$map(&$get_deploy_conf(),sub{
        my($comp,$conf)=@_; $$conf{project} ? [$comp=>$$conf{project}] : ()
    });
    my $form_options = &$encode({
        projectTags=>\@proj_tags,
        environments=>\@comp_proj,
        instance=>$instance,
    });
    return qq[<!DOCTYPE html><head><meta charset="UTF-8"></head>].
        qq[<body><script type="module">const formOptions=$form_options\n$client_code</script></body>];
};

my $gitlab_get_pipeline = sub{
    my($state_str,$builder_comp,$basic_img)=@_;
    my $state = &$decode($state_str);
    my $comp = $$state{environment}=~/^(\w[\w\-]+)$/ ? $1 : die;
    my $proj_tag = $$state{project}=~/^(\w[\w\-]+)$/ ? $1 : die;
    my $mode = $$state{mode}=~/^(base|next)$/ ? $1 : die;
    my $expires = $$state{expires}=~/([\w\s]+)/ ? $1 : die;
    my ($builder_repo,$commit) = $basic_img=~m{^(.+):common\.(\w+)$} ? ($1,$2) : die;
    my $img_tag = "$proj_tag.$mode.$commit";
    my $builder_img = "$builder_repo:$img_tag";
    my $conf = &$get_compose($comp);
    my $image_type = $$conf{image_type} || "";
    my $is_sandbox = $image_type eq "builder" ? 1 : $image_type eq "" ? 0 : die;
    my $runtime_img = $is_sandbox ? "$builder_img.sandbox" : do{
        my($runtime_repo) = &$get_deployer_conf($comp,1,qw[sys_image_repo]);
        "$runtime_repo:$img_tag"
    };
    my @vars = (variables => {"GIT_STRATEGY" => "none"});
    my $prod = "ssh-agent perl \$C4CI_PROTO_DIR/prod.pl";
    my @in_builder_img = (@vars, image => $builder_img);
    return &$encode({
        stages => [ "build_builder", "build_runtime", "up", "down" ],
        build_builder => {
            stage => "build_builder", @vars, image => $basic_img,
            script => ["$prod gitlab_build_builder $builder_comp $builder_img"],
        },
        build_runtime => {
            stage => "build_runtime", @in_builder_img,
            script => ["$prod gitlab_build_runtime $is_sandbox $builder_comp $runtime_img"],
        },
        (map{
            my $instance = $$_{instance}=~/^(\w[\w\-]+)$/ ? $1 : die;
            my $environment = $$_{environment}=~/^(\w[\w\-]+)$/ ? $1 : die;
            (
                "up-$instance" => {
                    stage       => "up", @in_builder_img,
                    script      => [
                        "C4INSTANCE=$instance C4IMAGE=$runtime_img $prod gitlab_up $environment down-$instance.sh",
                    ],
                    environment => {
                        name         => $environment,
                        on_stop      => "down-$instance",
                        $expires eq "never" ? () : (auto_stop_in => $expires),
                    },
                    artifacts => { paths => ["down-$instance.sh"] },
                },
                "down-$instance" => {
                    stage => "down", @in_builder_img,
                    script => ["sh down-$instance.sh"],
                    environment => {
                        name   => $environment,
                        action => "stop",
                    },
                    needs => ["up-$instance"],
                    "when" => "manual",
                },
            )
        } @{$$state{environments}||die}),
    });
};

push @tasks, ["gitlab_gen","",sub {
    my($out_path)=@_;
    &$ssh_add();
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $deploy_conf_url = syf("cat $local_dir/form.auth");
    my $state_str = syf("curl $deploy_conf_url/state.json");
    if($ENV{C4CI_STAGE} eq ""){
        my $index_html = "$deploy_conf_url/index.html";
        if($state_str eq ""){
            my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR=>\%ENV);
            my $instance = &$mandatory_of(C4INSTANCE=>\%ENV);
            my $conf_str = syf("cat $local_dir/c4dep.main.json");
            my $client_code = syf("cat $proto_dir/deploy_dialog.js");
            my $form_content = &$gitlab_get_form($instance,$conf_str,$client_code);
            sy("curl -X PUT $index_html --data-binary \@".&$put_temp("index.html"=>$form_content));
        } else {
            my $job_token = &$mandatory_of(CI_JOB_TOKEN=>\%ENV);
            my $git_project_id = &$mandatory_of(CI_PROJECT_ID=>\%ENV);
            my $server_url = &$mandatory_of(CI_SERVER_URL=>\%ENV);
            my $branch = &$mandatory_of(CI_COMMIT_REF_NAME=>\%ENV);
            my $url = "$server_url/api/v4/projects/$git_project_id/trigger/pipeline";
            my $temp = &$put_temp("trigger_payload"=>$state_str);
            sy("curl","-XPOST",$url,map{('--form',$_)} "token=$job_token","ref=$branch","variables[C4CI_STAGE]=INNER");
            print "\nYou can fill $index_html and retry\n";
        }
        &$put_text($out_path,"");
    } elsif($ENV{C4CI_STAGE} eq 'INNER') {
        my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
        my $basic_img = &$mandatory_of(CI_JOB_IMAGE => \%ENV);
        my $res = &$gitlab_get_pipeline($state_str,$builder_comp,$basic_img);
        print "\n$res\n";
        &$put_text($out_path,$res);
    } else { die }
}];

push @tasks, ["gitlab_build_builder","",sub{
    my($builder_comp,$builder_img) = @_;
    &$ssh_add();
    my @existing_images = map{/^(\S+)\s+(\S+)/?"$1:$2":()} syl(&$remote($builder_comp,"docker images"));
    return if grep{ $_ eq $builder_img } @existing_images;
    my $parse_img_name = sub{
        $_[0]=~/^(.+):([\w\-]+)\.(base|next)\.(\w+)$/ ? ["$1","$2","$3","$4"] : undef
    };
    my $builder_img_parsed = &$parse_img_name($builder_img) || die;
    my ($builder_repo,$builder_proj_tag,$builder_mode,$builder_commit) = @$builder_img_parsed;
    my $build = sub{
        my($dir,$steps)=@_;
        my $n_steps = join "", map{"RUN eval \$C4STEP_$_\n"} 0..2;
        &$put_text("$dir/Dockerfile","$steps\n$n_steps");
        &$gitlab_docker_build($dir,$builder_comp,$builder_img);
    };
    my $full = sub{
        my $basic_img = &$mandatory_of(CI_JOB_IMAGE=>\%ENV);
        &$build(&$get_tmp_dir(), "FROM $basic_img\nENV C4CI_BASE_TAG_ENV=$builder_proj_tag");
    };
    return &$full() if $builder_mode ne "next";
    my %existing_img_by_commit = map{
        my $parsed = &$parse_img_name($_);
        !$parsed ? () : do{
            my ($repo,$proj_tag,$mode,$commit) = @$parsed;
            $repo eq $builder_repo && $proj_tag eq $builder_proj_tag && $mode eq "base" ? ($commit=>$_) : ();
        }
    } @existing_images;
    my @commit_lengths = sort keys %{+{map{(length($_)=>1)} keys %existing_img_by_commit}};
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR=>\%ENV);
    my @log_commits = syf("cd $local_dir && git log --pretty=%H")=~/(\S+)/g;
    my @found_images = grep{$_} map{$existing_img_by_commit{$_}}
        map{my $c=$_;map{substr $c,0,$_}@commit_lengths} @log_commits;
    return &$full() if !@found_images;
    my $steps = "FROM $found_images[0]\nRUN \$C4STEP_RM\nCOPY --chown=c4:c4 . \$C4CI_BUILD_DIR";
    &$build($local_dir,$steps);
}];
push @tasks, ["gitlab_build_runtime","",sub{
    my($is_sandbox,$builder_comp,$runtime_img) = @_;
    &$ssh_add();
    if($is_sandbox eq "0"){
        &$gitlab_docker_build("/c4/res",$builder_comp,$runtime_img);
    } elsif($is_sandbox eq "1"){
        my $basic_img = &$mandatory_of(CI_JOB_IMAGE=>\%ENV);
        my $dir = &$get_tmp_dir();
        &$put_text("$dir/Dockerfile","FROM $basic_img\nENTRYPOINT exec perl \$C4CI_PROTO_DIR/sandbox.pl main");
        &$gitlab_docker_build($dir,$builder_comp,$runtime_img);
    } else { die }
}];
push @tasks, ["gitlab_up","",sub{
    my($comp,$out_path)=@_;
    &$ssh_add();
    my ($tmp_path,$options) = &$find_handler(ci_up=>$comp)->($comp);
    my $yml = &$make_kc_yml($comp,$tmp_path,$options);
    my $yml_str = join "\n", map{&$encode($_)} @$yml;
    my $kubectl = &$get_kubectl($comp);
    my $del_str = join " ", map{
        my $kind = lc($$_{kind} || die);
        my $nm = ($$_{metadata}||die)->{name} || die;
        "$kind/$nm"
    } @$yml;
    &$put_text($out_path,"$kubectl delete $del_str");
    sy("$kubectl apply -f ".&$put_temp("up.yml",$yml_str));
}];

# my $to_artifact = sub{"c4gen-gitlab-$_[0].yml"};
# my $get_map_tags_modes = sub{
#     my($local_dir) = @_;
#     my @tags = map{$$_[0] eq "C4TAG" ? $$_[1] : ()} @{&$decode(syf("cat $local_dir/c4dep.main.json"))};
#     return sub{
#         my($f)=@_;
#         map{ my $tag=$_; map{ &$f($tag,$_) } qw[base next] } @tags
#     };
# };
# my @replink = (
#     'export C4CI_BUILD_DIR=$CI_PROJECT_DIR',
#     'C4REPO_MAIN_CONF=$C4CI_BUILD_DIR/c4dep.main.replink /replink.pl',
#     'export C4CI_PROTO_DIR=$C4CI_BUILD_DIR/c4proto',
# );
# push @tasks, ["gitlab_pipeline","",sub{
#     my $local_dir = &$mandatory_of(C4CI_BUILD_DIR=>\%ENV);
#     my $basic_img = &$mandatory_of(CI_JOB_IMAGE=>\%ENV);
#     my $map_tags_modes = &$get_map_tags_modes($local_dir);
#     my @basic_img = (image=>$basic_img);
#     my @manual = ("when" => "manual");
#     my %out = (&$to_artifact("root")=>{
#         stages   => ["generate","expand"],
#         generate => {
#             stage => "generate", @basic_img,
#             script => [
#                 @replink,
#                 "perl ci.pl gitlab_pipeline_inner",
#             ],
#             artifacts => {
#                 paths => [&$map_tags_modes(sub{ my($tag,$md)=@_; &$to_artifact("$md-$tag") })],
#             },
#         },
#         &$map_tags_modes(sub{ my($tag,$md)=@_;
#             (
#                 "$md-$tag" => {
#                     stage => "expand", @manual,
#                     trigger => {
#                         include => [{job=>"generate",artifact=>&$to_artifact("$md-$tag")}]
#                     },
#                 }
#             )
#         }),
#     });
#     &$put_text("$local_dir/$_",&$encode($out{$_})) for sort keys %out;
# }];
# push @tasks, ["gitlab_pipeline_inner","",sub{
#     &$ssh_add();
#     my $local_dir = &$mandatory_of(C4CI_BUILD_DIR=>\%ENV);
#     my $commit = &$mandatory_of(CI_COMMIT_SHORT_SHA=>\%ENV);
#     #my $pipeline_id = &$mandatory_of(CI_PIPELINE_ID=>\%ENV);
#     my $basic_img = &$mandatory_of(CI_JOB_IMAGE=>\%ENV);
#     my $map_tags_modes = &$get_map_tags_modes($local_dir);
#     my @basic_img = (image=>$basic_img);
#     my @manual = ("when" => "manual");
#     my %out = &$map_tags_modes(sub{ my($proj_tag,$md)=@_;
#         my $img_tag = "$proj_tag.$md.$commit";
#         my ($builder_img,$runtime_img) = $basic_img=~m{^(.+)/\w+:[^:]+$} ?
#             ("$1/builder:$img_tag","$1/runtime:$img_tag") : die;
#         my @in_sandbox = (
#             image => $builder_img, variables => {GIT_STRATEGY=>"none"},
#         );
#         my @deploy = (stage => "deploy", @manual, @in_sandbox);
#         my @deploy_list = map{ref ? $_ : /(\S+)/g}
#             (&$get_compose_opt($proj_tag) || {})->{deploy_list};
#         my $mk_deployment = sub{
#             my($comp,$conf)=@_;
#             return () if $$conf{project} ne $proj_tag;
#             (
#                 "up-$comp" => {
#                     @deploy,
#                     script => [
#                         "export C4IMAGE_TAG=$img_tag",
#                         qq[perl \$C4CI_BUILD_DIR/ci.pl gitlab_up $comp],
#                     ],
#                     environment => {
#                         name => $comp,
#                         on_stop => "down-$comp",
#                         $$conf{auto_stop_in} ? (auto_stop_in => $$conf{auto_stop_in}):(), # 1 hour
#                     },
#                 },
#                 "down-$comp" => {
#                     @deploy,
#                     script => [
#                         qq[perl \$C4CI_BUILD_DIR/ci.pl gitlab_down $comp]
#                     ],
#                     environment => {
#                         name => $comp,
#                         action => "stop",
#                     },
#                 },
#             )
#         };
#         (&$to_artifact("$md-$proj_tag")=>{
#             stages           => [ "build_builder", "build_runtime", "deploy" ],
#             "bb-$md-$proj_tag"    => {
#                 stage => "build_builder", @basic_img,
#                 script => [
#                     @replink,
#                     "perl ci.pl gitlab_build_builder $builder_img",
#                 ],
#             },
#             "build_runtime"          => {
#                 stage => "build", @in_sandbox,
#                 script => [
#                     "perl \$C4CI_BUILD_DIR/ci.pl gitlab_build_runtime $runtime_img"
#                 ]
#             },
#             &$map(&$get_deploy_conf(),$mk_deployment)
#
#
#
#             #(map{&$mk_deployment($_)} @deploy_list),
#             # &$mk_deployment("dev",'$GITLAB_USER_NAME',$builder_img),
#             # &$mk_deployment("dynamic",$pipeline_id,$runtime_img),
#             # &$mk_deployment("stage","",$runtime_img),
#             # &$mk_deployment("prod","",$runtime_img),
#         }),
#     });
#     &$put_text("$local_dir/$_",&$encode($out{$_})) for sort keys %out;
# }];
# # deploy_list, auto_stop_in
# push @tasks, ["gitlab_up","",sub{
#     my($comp)=@_;
#     &$ssh_add();
#     my $conf = &$get_compose($comp);
#     my $instances =
#         $$conf{deploy_by} eq "" ? "" :
#         $$conf{deploy_by} eq "user" ? do{
#             my $user = &$mandatory_of(GITLAB_USER_NAME=>\%ENV);
#             my $count = $$conf{deploy_count} || 1;
#             join " ",map{"$user-$_"} 0..$count-1;
#         } :
#         die; # "dynamic" by $pipeline_id can be implemented here
#     my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR=>\%ENV);
#     sy(qq[C4INSTANCES="$instances" perl $proto_dir/prod.pl gitlab_up_inner]);
# }];
#
#
# push @tasks, ["gitlab_down","",sub{
#     my($comp)=@_;
#
# }];





# image_type builder, dynamic user

# static|user count main|gate

#todo: pull-tag-push between reg-s in "up", according to deployer settings
#my $conf = &$get_compose($builder_comp);
#my @allow = &$mandatory_of(C4CI_ALLOW=>$conf)=~/(\S+)/g;
#my $repo = (map{ m{(.+/builder):([^:]+)$} && $2 eq $proj_tag ? "$1":() } @allow)[0] ||
#    die "no repo for $proj_tag in $builder_comp";

# my $gitlab_docker_build = sub{
#     my($local_dir,$builder_comp,@args) = @_;
#     my $ctx_dir = syf("uuidgen")=~/(\S+)/ ? "$tmp_root/$1" : die;
#     &$rsync_to($local_dir,$builder_comp,$ctx_dir);
#     sy(&$ssh_ctl($builder_comp,"-t","docker","build",@args,$ctx_dir));
#     sy(&$ssh_ctl($builder_comp,"rm","-r",$ctx_dir));
# };
#
# push @tasks, ["gitlab_build_builder","",sub{
#     my($img)=@_;
#     my($repo,$tag,$proj_tag,$mode) =
#         $img=~/^(.+):(([^\.:]+)\.([^\.:]+)\.[^\.:]+)$/ ? ($1,$2,$3) : die;
#     &$ssh_add();
#     my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
#     my $local_dir = $ENV{C4CI_BUILD_DIR} || die "no C4CI_BUILD_DIR";
#     #
#     my $full_steps = syf("cat $local_dir/build.base.dockerfile");
#     my %has_tag = map{/^(\S+)\s+(\S+)/ && $1 eq $repo?("$2"=>1):()}
#         syl(&$remote($builder_comp,"docker images"));
#     return if $has_tag{$tag};
#     my ($steps,@args) = @{sub{
#         return if $mode ne "next";
#         my @found_tags = grep{$has_tag{$_}} map{"$proj_tag.base.$_"}
#             syf("git log --pretty=%h | head")=~/(\S+)/g;
#         return if !@found_tags;
#         my $next_steps = $full_steps=~/\n#!C4NEXT\s(.*)/s ? $1 : die "no #!C4NEXT";
#         return ["FROM $repo:$found_tags[0]\n$next_steps"];
#     }->()||[$full_steps,"--build-arg","C4CI_BASE_TAG=$proj_tag"]};
#     &$put_text("$local_dir/Dockerfile",$steps);
#     &$gitlab_docker_build($local_dir,$builder_comp,@args,"-t",$img);
# }];
# push @tasks, ["gitlab_build_runtime","",sub{
#     my($img)=@_;
#     my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
#     &$gitlab_docker_build("/c4/res",$builder_comp,"-t",$img)
# }];
# push @tasks, ["gitlab_dev_sandbox","",sub{
#     my $img = &$mandatory_of(CI_JOB_IMAGE=>\%ENV);
#     my $dev = &$mandatory_of(GITLAB_USER_NAME=>\%ENV)=~/^(\w+)$/ ? $1 : die "bad user name";
#
#
# }];






# my $ci_build_img = sub{
#     my ($builder_comp,$arg) = @_;
#     my $conf = &$get_compose($builder_comp);
#     my $allow = &$mandatory_of(C4CI_ALLOW=>$conf);
#     my($full_img,$reg,$shrep,$tag,$base,$proj,$mode,$checkout) =
#         $arg=~/^(([\w\-\.\:\/]*?)(\w+)\:((([\w\-]+)[\w\.]*)\.(\w+)\.([\w\-]+)))$/ ?
#         ($1,$2,$3,$4,$5,$6,$7,$8) : die "can not [$arg]";
#     index(" $allow "," $reg$shrep:$proj ") < 0 and die "prefix not allowed";
#     my $builder_reg = index(" $allow "," ${reg}builder:$proj ") < 0 ? "" : $reg;
#     #implement checkout lock?
#     my $builder = &$md5_hex($full_img)."-".time;
#     my %repo_urls = &$mandatory_of(C4CI_SHORT_REPO_REMOTES=>$conf)=~/(\S+)/g;
#     my $repo_url = $repo_urls{$shrep} || die "no repo url for $shrep";
#     my $repo_dir = &$need_path("/tmp/c4ci/$shrep");
#     -e $repo_dir or sy("mkdir -p /tmp/c4ci && git clone $repo_url $repo_dir");
#     my $local_dir = &$get_tmp_dir();
#     sy("cd $repo_dir && git fetch && git fetch --tags && ".
#       "git --git-dir=$repo_dir/.git --work-tree=$local_dir checkout $checkout -- .");
#     my $ctx_dir = syf("uuidgen")=~/(\S+)/ ? "$tmp_root/$1" : die;
#     &$rsync_to($local_dir,$builder_comp,$ctx_dir);
#     my @args = ("--build-arg","C4CI_BASE_TAG=$base");
#     my @commands = (
#         ["-t","docker","build","-t","builder:$tag","-f","$ctx_dir/build.$mode.dockerfile",@args,$ctx_dir],
#         $builder_reg ? (
#             ["docker","tag","builder:$tag","${builder_reg}builder:$tag"],
#             ["docker","push","${builder_reg}builder:$tag"],
#         ) : (),
#         ["rm","-r",$ctx_dir],
#         ["docker","create","--name",$builder,"builder:$tag"],
#         ["docker","cp","$builder:/c4/res",$ctx_dir],
#         ["docker","rm","-f",$builder],
#         ["-t","docker","build","-t",$full_img,$ctx_dir],
#         $reg ? ["docker","push",$full_img] : (),
#     );
#     sy(&$ssh_ctl($builder_comp,@$_)) for @commands;
#     print "C4IMAGE=$arg prod up \$C4CURRENT_STACK\n";
# };
#
# my $is_builder_repo = sub{ $_[0]=~m{/([^/]+)$} && $1 eq 'builder' };
# my $ci_build_proj_tag = sub{
#     my ($proj_tag_arg,$rebuild_base) = @_;
#     my $proj_tag = $proj_tag_arg || $ENV{C4CI_BASE_TAG_ENV} || die 'proj-tag not found';
#     my $comp = $ENV{C4COMPOSITION} || die "no C4COMPOSITION";
#     my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
#     print "builder: $builder_comp\n";
#     #
#     my $conf = &$get_compose($builder_comp);
#     my @proj_repos = map{ m{(.+):([^:]+)$} && $2 eq $proj_tag ? "$1":() }
#         ($$conf{C4CI_ALLOW}||die)=~/(\S+)/g;
#     my $repo = (grep{ !&$is_builder_repo($_) } @proj_repos)[0] || die 'proj-tag not allowed';
#     #
#     my ($head,@log) = syf("cat $ENV{C4CI_BUILD_DIR}/target/c4git-log")=~/(\S+)/g;
#     $head || die 'commit not found';
#     my $ideal_tag = "$proj_tag.base.$head";
#     my @next_tags = $rebuild_base ? () : do{
#         my %has_img = map{/^(\S+)\s+(\S+)/?("$1:$2"=>1):()}
#             syl(&$remote($builder_comp,"docker images"));
#         my $builder_repo = (grep{ &$is_builder_repo($_) } @proj_repos)[0] || die 'proj-tag builder not allowed';
#         $has_img{"$repo:$ideal_tag"} ? () :
#             map{ $has_img{"$builder_repo:$_"} ? "$_.next.$head" : () }
#             map{"$proj_tag.base.$_"} @log
#     };
#     my ($target_tag) = (@next_tags,$ideal_tag);
#     #
#
#     #print join "",map{"## $_\n"} @next_tags,$ideal_tag;
#
#     &$ci_build_img($builder_comp,"$repo:$target_tag");
#     my @cont = map{
#         &$is_builder_repo($_) ? "C4IMAGE=$_:$target_tag prod up $comp" : (
#             "prod ssh $builder_comp docker tag $repo:$target_tag $_:$target_tag",
#             "prod ssh $builder_comp docker push $_:$target_tag",
#         )
#     } grep{$_ ne $repo} @proj_repos;
#     @cont and print join "", map{"$_\n"} "### what's next:", @cont;
# };
#
# push @tasks, ["build_img","<builder> [img]",sub{
#     my ($builder_comp,$img) = @_;
#     &$ssh_add();
#     if(defined $img){
#         &$ci_build_img($builder_comp,$img);
#     } else {
#         my $conf = &$get_compose($builder_comp);
#         print sort map{"$_\n"} ($$conf{C4CI_ALLOW}||die)=~/(\S+)/g;
#     }
# }];
# push @tasks, ["build_img_next","[proj-tag]",sub{
#     my ($proj_tag) = @_;
#     &$ssh_add();
#     &$ci_build_proj_tag($proj_tag,0);
# }];
# push @tasks, ["build_img_base","[proj-tag]",sub{
#     my ($proj_tag) = @_;
#     &$ssh_add();
#     &$ci_build_proj_tag($proj_tag,1);
# }];


my $ci_inner_opt = sub{
    map{$ENV{$_}||die $_} qw[C4CI_BASE_TAG_ENV C4CI_BUILD_DIR C4CI_PROTO_DIR];
};
push @tasks, ["ci_inner_build","",sub{
    my ($base,$gen_dir,$proto_dir) = &$ci_inner_opt();
    sy("perl $proto_dir/bloop_fix.pl");
    sy("bloop server &");
    my $find = sub{ syf("jcmd")=~/^(\d+)\s+(\S+\bblp-server|bloop\.Server)\b/ and return "$1" while sleep 1; die };
    my $pid = &$find();
    sy("cd $gen_dir && perl $proto_dir/build.pl");
    my $close = &$start("cd $gen_dir && sh .bloop/c4/tag.$base.compile");
    print "tracking compiler 0\n";
    my $n = 0;
    while(syf("ps -ef")=~/\bbloop\s+compile\b/){
        my $thread_print = syf("jcmd $pid Thread.print");
        $n = $thread_print=~/\bBloopHighLevelCompiler\b/ ? 0 : $n+1;
        sy("kill $pid"), print($thread_print), die if $n > 15;
        sleep 1;
    }
    print "tracking compiler 1\n";
    &$close();
    print "tracking compiler 2\n";
}];

my $client_mode_to_opt = sub{
    my($mode)=@_;
    $mode eq "fast" ? "--env.fast=true --mode development" :
    $mode eq "dev" ? "--mode development" :
    "--mode production";
};
my $if_changed = sub{
    my($path,$will,$then)=@_;
    return if (-e $path) && syf("cat $path") eq $will;
    my $res = &$then();
    &$put_text($path,$will);
    $res;
};
my $build_client = sub{
    my($gen_dir, $opt)=@_;
    $gen_dir || die;
    my $dir = "$gen_dir/.bloop/c4/client";
    my $build_dir = "$dir/out";
    unlink or die $! for <$build_dir/*>;
    my $conf_dir = &$single_or_undef(grep{-e} map{"$_/webpack"} <$dir/src/*>) || die;
    &$if_changed("$dir/package.json", syf("cat $conf_dir/package.json"), sub{1})
        and sy("cd $dir && npm install");
    sy("cd $dir && cp $conf_dir/webpack.config.js . && node_modules/webpack/bin/webpack.js $opt");# -d
    &$put_text("$build_dir/publish_time",time);
    &$put_text("$build_dir/c4gen.ht.links",join"",
        map{ my $u = m"^/(.+)$"?$1:die; "base_lib.ee.cone.c4gate /$u $u\n" }
        map{ substr $_, length $build_dir }
        sort <$build_dir/*>
    );
};

push @tasks, ["build_client","<dir> [mode]",sub{
    my($dir,$mode)=@_;
    &$build_client($dir, &$client_mode_to_opt($mode));
}];
push @tasks, ["build_client_changed","<dir> [mode]",sub{
    my($dir,$mode)=@_;
    $dir || die;
    &$if_changed("$dir/.bloop/c4/client-sums-compiled",syf("cat $dir/.bloop/c4/client-sums"),sub{
        &$build_client($dir, &$client_mode_to_opt($mode));
    });
}];
push @tasks, ["ci_inner_cp","",sub{ #to call from Dockerfile
    my ($base,$gen_dir,$proto_dir) = &$ci_inner_opt();
    #
    my $ctx_dir = "/c4/res";
    -e $ctx_dir and sy("rm -r $ctx_dir");
    sy("mkdir $ctx_dir");
    &$put_text("$ctx_dir/.dockerignore",".dockerignore\nDockerfile");
    &$put_text("$ctx_dir/Dockerfile", join "\n",
        &$prod_image_steps(),
        (grep{$_} syf("cat $gen_dir/.bloop/c4/tag.$base.steps")),
        "ENV JAVA_HOME=/tools/jdk",
        "RUN chown -R c4:c4 /c4",
        "WORKDIR /c4",
        "USER c4",
        "RUN cd /tools/greys && bash ./install-local.sh",
        "COPY --chown=c4:c4 . /c4",
        'ENTRYPOINT ["perl","run.pl"]',
    );
    sy("cp $proto_dir/$_ $ctx_dir/$_") for "install.pl", "run.pl";
    #
    mkdir "$ctx_dir/app";
    my $mod     = syf("cat $gen_dir/.bloop/c4/tag.$base.mod" )=~/(\S+)/ ? $1 : die;
    my $main_cl = syf("cat $gen_dir/.bloop/c4/tag.$base.main")=~/(\S+)/ ? $1 : die;
    my $paths = &$decode(syf("cat $gen_dir/.bloop/c4/mod.$mod.classpath.json"));
    my @classpath = $$paths{CLASSPATH}=~/([^\s:]+)/g;
    my @started = map{&$start($_)} map{
        m{([^/]+\.jar)$} ? "cp $_ $ctx_dir/app/$1" :
        m{([^/]+)\.classes(-bloop-cli)?$} ? "cd $_ && zip -q -r $ctx_dir/app/$1.jar ." : die $_
    } @classpath;
    &$_() for @started;
    &$put_text("$ctx_dir/serve.sh","export C4APP_CLASS=$main_cl\nexec java ee.cone.c4actor.ServerMain");
    #
    my %has_mod = map{m"/mod\.([^/]+)\.classes(-bloop-cli)?$"?($1=>1):()} @classpath;
    my @public_part = map{ my $dir = $_;
        my @pub = map{ !/^(\S+)\s+\S+\s+(\S+)$/ ? die : $has_mod{$1} ? [$_,"$2"] : () }
            syf("cat $dir/c4gen.ht.links")=~/(.+)/g;
        my $sync = [map{"$$_[1]\n"} @pub];
        my $links = [map{"$$_[0]\n"}@pub];
        @pub ? +{ dir=>$dir, sync=>$sync, links=>$links } : ()
    } grep{-e $_} $$paths{C4PUBLIC_PATH}=~/([^\s:]+)/g;
    for my $part(@public_part){
        my $from_dir = $$part{dir} || die;
        my $files = &$put_temp("sync", join "", @{$$part{sync}||die});
        sy("rsync -av --files-from=$files $from_dir/ $ctx_dir/htdocs");
    }
    @public_part and &$put_text("$ctx_dir/htdocs/c4gen.ht.links",join"",map{@{$$_{links}||die}}@public_part);
}];
push @tasks, ["ci_rm","",sub{ #to call from Dockerfile
    my ($base,$gen_dir,$proto_dir) = &$ci_inner_opt();
    &$ignore($base,$proto_dir);
    print "[purging]\n";
    for(sort{$b cmp $a} syl("cat $gen_dir/ci.rm")){ chomp $_ or die "[$_]"; unlink $_; rmdir $_ }
}];

my $make_frp_image = sub{
    my ($comp) = @_;
    my $gen_dir = &$get_proto_dir();
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    sy("cp $gen_dir/install.pl $from_path/");
    &$put("frp.pl", join "\n",
        '$ENV{C4FRPC_INI} and exec "/tools/frp/frpc", "-c", $ENV{C4FRPC_INI};',
        '$ENV{C4FRPS_INI} and exec "/tools/frp/frps", "-c", $ENV{C4FRPS_INI};',
        'die;'
    );
    &$put("Dockerfile", join "\n",
        &$base_image_steps(),
        "RUN perl install.pl apt curl ca-certificates",
        "RUN perl install.pl curl $dl_frp_url",
        "COPY frp.pl /",
        "USER c4",
        'ENTRYPOINT ["perl", "/frp.pl"]',
    );
    return &$remote_build(''=>$comp,$from_path);
};

#my

push @tasks, ["up-frp_client", "", sub{
    my ($comp) = @_;
    my $img = &$make_frp_image($comp);
    my $options = {
        image => $img, C4FRPC_INI => "/c4conf/frpc.ini", @req_small,
    };
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    &$wrap_deploy($comp,$from_path,$options);
}];

my $extendable_visit = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $gate_comp = $$conf{ca};
    my @http_client = !$gate_comp ? () : do{
        my %consumer_options = &$get_consumer_options($gate_comp);
        warn "sse will not be multiplexed";
        $consumer_options{C4HTTP_SERVER}=~m{^(http)://(.+):(\d+)$} ? [$1,$3,$2] : die;
    };
    my @connects = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^frpc:(\w+)$/ ? ["$1",$v=~/^(.+):(\d+)$/?($2,$1):die] : ()
    });
    [@http_client,@connects]
};
push @tasks, ["visit-frp_client", "", $extendable_visit];
push @tasks, ["visit-consumer", "", $extendable_visit];

my $get_visitor_conf = sub{
    my ($comp) = @_;
    my $services = &$find_handler(visit=>$comp)->($comp);
    map{ my($name,$port,$host) = @$_; &$ignore($host); [$port=>"$comp.$name"] } @$services;
};

push @tasks, ["up-visitor", "", sub{
    my ($comp) = @_;
    my $conf = &$get_compose($comp);
    my $server_comp = $$conf{peer};
    my $img = &$make_frp_image($comp);
    my @services = $server_comp ? &$get_visitor_conf($server_comp) : ();
    my @ports = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^port:/ ? ($k=>$v) : ()
    });
    my @visits = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^visit:(\d+)$/ ? ["$1"=>$v] : ()
    });
    my @add_ports = map{ my($port,$nm)=@$_; &$ignore($nm); ("port:$port:$port"=>"") } @visits;
    my $options = {
        image => $img, C4FRPC_INI => "/c4conf/frpc.visitor.ini",
        @ports, @add_ports, @req_small,
    };
    my $from_path = &$get_tmp_dir();
    &$make_visitor_conf($comp,$from_path,[@services,@visits]);
    &$wrap_deploy($comp,$from_path,$options);
}];

push @tasks, ["up-s3client", "", sub{
    my ($comp) = @_;
    my $img = do{
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        &$put("Dockerfile", join "\n",
            "ARG C4UID=1979",
            "FROM ghcr.io/conecenter/c4replink:v2",
            "USER root",
            "RUN perl install.pl apt curl ca-certificates",
            "RUN /install.pl curl https://dl.min.io/client/mc/release/linux-amd64/mc && chmod +x /tools/mc",
            q{ENTRYPOINT /tools/mc alias set def $(cat $C4S3_CONF_DIR/address) $(cat $C4S3_CONF_DIR/key) $(cat $C4S3_CONF_DIR/secret) && exec sleep infinity }
        );
        &$remote_build(''=>$comp,$from_path);
    };
    my $options = {
        image => $img, C4S3_CONF_DIR => "/c4conf-ceph-client", @req_small,
    };
    my $from_path = &$get_tmp_dir();
    &$wrap_deploy($comp,$from_path,$options);
}];

# my $install_kubectl = sub{
#     "RUN /install.pl curl https://storage.googleapis.com/kubernetes-release/release/v1.14.0/bin/linux/amd64/kubectl "
#     ."&& chmod +x /tools/kubectl "
# };
my $install_tini = sub{
    "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini"
    ."&& chmod +x /tools/tini"
};

push @tasks, ["up-kc_host", "", sub{ # the last multi container kc
    my ($comp) = @_;
    my $conf = &$get_compose($comp);
    my $ns = &$mandatory_of(ns=>$conf);
    my $kubectl = "kubectl -n $ns";
    my $run_comp = "deployer";
    my $add_yml = join "\n", map{&$encode($_)} ({
        apiVersion => "rbac.authorization.k8s.io/v1",
        kind => "Role",
        metadata => { name => $run_comp },
        rules => [
            {
                apiGroups => ["","apps"],
                resources => ["statefulsets","secrets","services","deployments"],
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
                resources => ["ingresses","deployments"],
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
    my $get_secret = qq[$kubectl get secret -o jsonpath='{.data.token}' \$($kubectl get serviceaccount $run_comp -o jsonpath='{.secrets[].name}') | base64 -d];
    print "######## COPY:\ncat <<EOF | $kubectl apply -f- \n$add_yml\nEOF\necho SECRET: && $get_secret && echo\n######## END_COPY\n";
}];

###

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
    my($ts,$internal_src)=@_;
    &$mk_to_cfg().join "\n",
        "frontend fe443",
        "  bind :443 ssl crt-list /etc/letsencrypt/haproxy/list.txt",
        "  acl internal_src src $internal_src",
        (map{"  use_backend be_$$_[0] if internal_src { ssl_fc_sni -m dom $$_[0] }"}@$ts),
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

push @tasks, ["up-proxy2","",sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $external_ip  = $$conf{external_ip} || die "no external_ip";
    my $internal_src = $$conf{internal_src} || die "internal_src";
    my $yml_str = &$mk_to_yml($external_ip);
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    my @pass = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^pass:(.+)$/ ? [$1=>$v] : ()
    });
    &$put("docker-compose.yml",$yml_str);
    &$put("http.cfg",&$mk_to_http($external_ip));
    &$put("https.cfg",&$mk_to_https(\@pass,$internal_src));
    &$sync_up($comp,$from_path,"#!/bin/bash\ndocker-compose up -d --remove-orphans --force-recreate");
}];

push @tasks, ["up-frps","",sub{
    my($comp)=@_;
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
    my $options = {
        image => $img,
        C4FRPS_INI => "/c4conf/frps.ini",
        "port:7000:7000" => "",
        "port:7500:7500" => "",
        "port:$ext_ip:$vhost_https_port:$local_https_port" => "",
        "port:$ext_ip:$vhost_http_port:$local_http_port" => "",
        @req_small,
    };
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    &$put("frps.ini", $conf_content);
    &$wrap_deploy($comp,$from_path,$options);
}];

push @tasks, ["cert","$composes_txt [hostname]",sub{
  my($comp,$nm)=@_;
  &$ssh_add();
  my $conf = &$get_compose($comp);
  my $cert_mail = $$conf{cert_mail} || die;
  my $exec = sub{&$remote($comp,"docker exec -i $comp\_certbot_1 sh").' < '.&$put_temp(@_)};
  sy(&$exec("cert-only.sh","certbot certonly --standalone -n --email '$cert_mail' --agree-tos -d $nm")) if defined $nm;
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
my $sleep = sub{ select undef, undef, undef, $_[0] };
my $tp_run = sub{
    my($wrap)=@_;
    my $cmd = &$wrap("jcmd");
    my $get_pids = sub{
        sort{$b<=>$a} map{/^(\d+)\s+(ee\.cone\.\S+)/  ?"$1":()} syl($cmd);
    };
    my $prn = sub{
        my @pid = @_;
        my $p_cmd = &$wrap(join " && ", map{"jcmd $_ Thread.print"} @pid);
        while(1){
            &$sleep(0.25);
            print grep{ !/\.epollWait\(/ && /\sat\s/ } &$tp_split(syf($p_cmd));
        }
    };
    ($get_pids,$prn);
};
push @tasks, ["thread_print_local_next"," ",sub{
    my($get_pids,$prn) = &$tp_run(sub{"$_[0]"});
    my %was = map{($_=>1)} &$get_pids();
    my $wait_next = sub{
        while(1){
            &$sleep(0.25);
            $was{$_} or return $_ for &$get_pids();
        }
    };
    my $pid = &$wait_next();
    &$prn($pid);
}];
push @tasks, ["thread_print_local_max"," ",sub{
    my($get_pids,$prn) = &$tp_run(sub{"$_[0]"});
    my @pid = &$get_pids();
    @pid || return;
    &$prn($pid[0]);
}];
push @tasks, ["thread_print","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    my @pods = &$get_pods($comp);
    my($get_pids,$prn) = &$tp_run(sub{ my($cmd)=@_; join " && ", map{"($_)"} map{&$kj_exec($comp,$_,"",$cmd)} @pods });#/RUNNABLE/
    my @pid = &$get_pids();
    @pid || return;
    &$prn(@pid);
}];
push @tasks, ["thread_grep_cut","<substring>",sub{
    my($v)=@_;
    print map{ my $i = index $_,$v; $i<0?():substr($_,0,$i)."\n\n" } &$tp_split(join '',<STDIN>);
}];
push @tasks, ["thread_grep_sub","<expression>",sub{
    my($body)=@_;
    my $expr = q^sub{ my $at0=/(.*\bat\b.*)/?$1:''; ^.$body.q^}^;
    my $by = eval $expr;
    die "$@ -- $expr" if defined $@;
    print grep{&$by} &$tp_split(join '',<STDIN>);
}];
push @tasks, ["thread_count"," ",sub{
    my @r = grep{/\S/} &$tp_split(join '',<STDIN>);
    print scalar(@r)."\n";
}];

push @tasks, ["exec_repl","<pod|$composes_txt>",sub{
    my($arg)=@_;
    &$ssh_add();
    &$for_comp_pod($arg, sub{ my ($comp, $pod) = @_;
        sy(&$kj_exec($comp,$pod,"-it","test -e /c4/.ssh/id_rsa || ssh-keygen;ssh localhost -p22222"));
    });
}];
push @tasks, ["greys_local","<pid>",sub{
    my($pid)=@_;
    $pid || die;
    -e "$ENV{HOME}/.greys" or sy("cd /tools/greys && bash ./install-local.sh");
    sy("/tools/greys/greys.sh $pid");
}];
push @tasks, ["greys","<pod|$composes_txt>",sub{
    my($arg)=@_;
    &$ssh_add();
    &$for_comp_pod($arg, sub{ my ($comp, $pod) = @_;
        sy(&$kj_exec($comp,$pod,"-it","/tools/greys/greys.sh 1"));
    });
}];

push @tasks, ["exec_install","<pod|$composes_txt> <tgz>",sub{
    my($arg,$tgz)=@_;
    &$ssh_add();
    my ($comp,@pods) = &$get_comp_pods($arg);
    sy(&$kj_exec($comp,$_,"-i","tar -xz")." < $tgz") for @pods;
}];
push @tasks, ["cat_visitor_conf","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    my @services = &$get_visitor_conf($comp);
    my $from_path = &$get_tmp_dir();
    &$make_visitor_conf($comp,$from_path,[@services]);
    sy("cat $from_path/frpc.visitor.ini");
}];

push @tasks, ["up-elector","",sub{
    my ($comp) = @_;
    my $img = do{
        my $gen_dir = &$get_proto_dir();
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        sy("cp $gen_dir/install.pl $gen_dir/elector.js $from_path/");
        &$put("Dockerfile", join "\n",
            &$base_image_steps(),
            "RUN perl install.pl apt curl ca-certificates xz-utils", #xz-utils for node
            "RUN perl install.pl curl $dl_node_url",
            &$install_tini(),
            "COPY elector.js /",
            "USER c4",
            'ENTRYPOINT ["/tools/tini","--","/tools/node/bin/node","/elector.js"]',
        );
        &$remote_build(''=>$comp,$from_path);
    };
    my $from_path = &$get_tmp_dir();
    my $options = {
        image => $img, tty => "true", headless => 1, replicas => 3,
        C4HTTP_PORT => $elector_port, "port:$elector_port:$elector_port"=>"",
        @req_small
    };
    &$wrap_deploy($comp,$from_path,$options);
}];

#ssh-keygen -f id_rsa
push @tasks, ["up-dconf","",sub{
    my ($comp) = @_;
    my $img = do{
        my $gen_dir = &$get_proto_dir();
        my $from_path = &$get_tmp_dir();
        my $put = &$rel_put_text($from_path);
        sy("cp $gen_dir/install.pl $gen_dir/dconf.js $from_path/");
        &$put("Dockerfile", join "\n",
            &$base_image_steps(),
            "RUN perl install.pl apt curl ca-certificates xz-utils openssh-client", #xz-utils for node
            "RUN perl install.pl curl $dl_node_url",
            &$install_tini(),
            "COPY dconf.js /",
            "USER c4",
            'ENTRYPOINT ["/tools/tini","--","/tools/node/bin/node","/dconf.js"]',
        );
        &$remote_build(''=>$comp,$from_path);
    };
    my $from_path = &$get_tmp_dir();
    my $hostname = &$get_hostname($comp) || die "no le_hostname";
    my $cmd = do{
        my($host,$port,$path) = &$get_deploy_location();
        my $dir = "/c4/.ssh";
        "mkdir -p $dir && cp \$C4DEPLOY_DIR/* \$C4KNOWN_HOSTS_DIR/* $dir && chmod 0700 $dir && chmod 0600 $dir/*".
        " && ssh -p $port c4\@$host 'cd $path && git pull'"
    };
    my $options = {
        image => $img,
        tty => "true",
        "port:$inner_http_port:$inner_http_port"=>"",
        "ingress:$hostname/"=>$inner_http_port,
        C4HTTP_PORT => $inner_http_port,
        C4DEPLOY_DIR=>"/c4conf-".&$mandatory_of(C4DEPLOY_SECRET_NAME=>\%ENV),
        C4KNOWN_HOSTS_DIR=>"/c4conf-".&$mandatory_of(C4KNOWN_HOSTS_SECRET_NAME=>\%ENV),
        C4CI_CMD => $cmd,
        @req_small
    };
    &$wrap_deploy($comp,$from_path,$options);
}];

my $dir_to_secret = sub{
    my($kubectl,$secret_name,$dir)=@_;
    -e $dir or die;
    my $data = {map{
        my $k = substr $_, 1+length $dir;
        my $v = syf("base64 -w0 < $_");
        ($k,$v)
    } sort <$dir/*>};
    my $secret = &$encode({
        apiVersion => "v1", kind => "Secret", type => "Opaque", data => $data,
        metadata => { name => $secret_name },
    });
    syf("$kubectl apply -f ".&$put_temp("secret",$secret));
};

my $add_known_host = sub{
    my($host)=@_;
    my $dir = &$get_tmp_dir();
    my $kubectl = &$get_kubectl_raw(&$mandatory_of(C4DEPLOY_CONTEXT=>\%ENV));
    my $secret_name = &$mandatory_of(C4KNOWN_HOSTS_SECRET_NAME=>\%ENV);
    &$secret_to_dir($kubectl,$secret_name,$dir);
    my $path = "$dir/known_hosts";
    my $known = syf("cat $path");
    my @add_known = grep{index($known,$_)<0} syl("ssh-keyscan -H $host");
    @add_known or return;
    &$put_text($path,join"",$known,@add_known);
    &$dir_to_secret($kubectl,$secret_name,$dir);
};

push @tasks, ["up-dc_host","",sub{
    my ($comp) = @_;
    my ($host,$port,$user) = &$get_host_port($comp);
    sy("ssh-copy-id $user\@$host -p $port");
    do{
        my $groups = syf(&$remote($comp,"groups"));
        " $groups "=~/\sdocker\s/ or sy(&$ssh_ctl($comp,"-t","sudo usermod -aG docker $user"));
    };
    &$add_known_host($host);
}];

my $ckh_secret_dir =sub{ my $nm = &$ckh_secret($_[0]); ($nm,"c4conf-$nm") };
push @tasks, ["secret_get","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    &$ssh_add();
    rename $dir, "$dir-".time or die if -e $dir;
    my $kubectl = &$get_kubectl($comp);
    &$secret_to_dir($kubectl,$secret_name,$dir);
}];

push @tasks, ["secret_set","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    &$ssh_add();
    my $kubectl = &$get_kubectl($comp);
    &$dir_to_secret($kubectl,$secret_name,$dir);
}];

####

&$main(@ARGV);
&$cleanup();
