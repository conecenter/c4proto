#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

my $sys_image_ver = "v86";

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

sub cached(&){
    my($calc)=@_;
    my %h;
    sub{ my($k)=@_; ($h{$k}||=[scalar &$calc($k)])->[0] }
};

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
    my $yml_str = JSON::XS->new->canonical(1)->encode($generated);
    $yml_str=~s/("\w+":\s*)"(true|false)"/$1$2/g;
    $yml_str
};

my $get_kubectl_raw = sub{"kubectl --context $_[0]"};

my $ckh_secret =sub{ $_[0]=~/^([\w\-\.]{3,})$/ ? "$1" : die 'bad secret name' };

my $secret_to_dir_decode = sub{
    my($str,$dir) = @_;
    my $data = &$decode($str)->{data} || die;
    for(sort keys %$data){
        my $v64 = $$data{$_};
        my $fn = &$need_path("$dir/".&$ckh_secret($_));
        sy("base64 -d > $fn < ".&$put_temp("value",$v64));
    }
};

my $get_secret_str = sub{
    my($kubectl,$secret_name,$required)=@_;
    my $arg = $required ? "" : "--ignore-not-found";
    syf("$kubectl get secret/$secret_name -o json $arg");
};

my $secret_to_dir = sub{
    my($kubectl,$secret_name,$dir)=@_;
    &$secret_to_dir_decode(&$get_secret_str($kubectl,$secret_name,1),$dir);
};

my $secret_yml_from_files = sub{
    my($name,$data)=@_;
    +{
        apiVersion => "v1",
        kind => "Secret",
        metadata => { name => $name },
        type => "Opaque",
        data => { map{($_=>syf("base64 -w0 < $$data{$_}"))} sort keys %$data },
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

my $get_conf_dir = cached{
    my($host,$port,$path) = &$get_deploy_location();
    my $remote_loc = "c4\@$host:$path";
    my $rsync = "rsync -e 'ssh -p $port' -a --del";
    my $local_dir = &$get_tmp_dir();
    sy("$rsync $remote_loc/ $local_dir");
    [$local_dir,"$rsync $local_dir/ $remote_loc"];
};

my $single_or_undef = sub{ @_==1 ? $_[0] : undef };

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };

my $resolve = cached{
    my($key)=@_;
    my($dir,$save) = @{&$get_conf_dir('')};
    &$ignore($save);
    my $conf_all = require "$dir/$key.pl";
    my @handlers = &$map($conf_all,sub{ my($k,$v)=@_;
        "CODE" eq ref $v ? [$k,$v] : ()
    });
    my $re = join "|",
        map{ my($k,$v) = @{$handlers[$_]||die}; &$ignore($v); "(?<p$_>$k)" }
            0..$#handlers;
    my %handlers = map{ ("p$_"=>$handlers[$_]||die) } 0..$#handlers;
    return cached {
        my ($comp) = @_;
        $$conf_all{$comp} || &$single_or_undef(map{
            my($k,$v) = @{$handlers{$_}||die};
            $comp=~/^$k$/ ? {&$v(@{^CAPTURE})} : die
        } $comp=~/^($re)$/ ? keys %+ : ())
    }
};

my $get_compose = sub{ &$resolve('main')->($_[0]) || die "composition expected $_[0]" };

my $get_deployer = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    $$conf{deployer} || $comp;
};
my $get_deployer_conf = sub{
    my($comp,$chk,@k)=@_;
    my $deployer = &$get_deployer($comp);
    my $n_conf = &$get_compose($deployer);
    map{$$n_conf{$_} || $chk && die "$deployer has no $_"} @k;
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
    my($dir,$save) = @{&$get_conf_dir('')};
    sy("mcedit","$dir/auth.pl");
    sy($save);
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
    my $conf = &$get_compose($comp);
    $$conf{le_hostname} || $$conf{type} eq "gate" && do{
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
    &$resolve('main')->($arg) ? ($arg,&$get_pods($arg)) :
        $_[0]=~/^(.+)-\d$/ ? ("$1",$arg) : $_[0]=~/^(.+)-\w+-\w+$/ ? ("$1",$arg) :
            die "bad composition or pod"
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

my $spaced_list = sub{ map{ ref($_) ? @$_ : /(\S+)/g } @_ };

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
            env=>[
                $$opt{need_pod_ip} ? {name=>"C4POD_IP",valueFrom=>{fieldRef=>{fieldPath=>"status.podIP"}}} : (),
                @env
            ],
            volumeMounts=>[@secret_mounts,@host_mounts],
            $$opt{tty} ? (tty=>$$opt{tty}) : (),
            securityContext => { allowPrivilegeEscalation => "false" },
            resources => {
                limits => {
                    cpu => $$opt{lim_cpu} || "64",
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
            (exists $$opt{replicas}) ? (replicas=>$$opt{replicas}) : (),
            selector => { matchLabels => { app => $name } },
            template => {
                metadata => {
                    labels => { app => $name },
                },
                spec => {
                    containers => [$container],
                    volumes => [@secret_volumes, @host_volumes],
                    hostAliases => \@host_aliases,
                    imagePullSecrets => [
                        map{+{name=>$_}}
                            &$spaced_list(&$mandatory_of(image_pull_secrets=>$opt))
                    ],
                    securityContext => {
                        runAsUser => 1979,
                        runAsGroup => 1979,
                        fsGroup => 1979,
                        runAsNonRoot => "true",
                    },
                    #$$opt{is_deployer} ? (serviceAccountName => "deployer") : (),
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
        my @tls_annotations = $disable_tls || $ingress_secret_name ? () :
            ("cert-manager.io/cluster-issuer" => "letsencrypt-prod");
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
            metadata => {
                name => $name,
                annotations=>{
                    "kubernetes.io/ingress.class" => "nginx",
                    "nginx.ingress.kubernetes.io/proxy-read-timeout" => "150",
                    "nginx.ingress.kubernetes.io/proxy-send-timeout" => "150",
                    @tls_annotations,
                },
            },
            spec => { rules => \@rules, @tls },
        } : ();
    };
    #
    my @secrets_yml = map{
        my %data = map{($_=>"$tmp_path/$_")} @{$$_{files}||die};
        &$secret_yml_from_files(&$mandatory_of(secret=>$_), \%data);
    } @int_secrets;
    #
    [@secrets_yml, @service_yml, @ingress_yml, $stateful_set_yml];
};

my $add_image_pull_secrets = sub{
    my ($name,$options) = @_;
    my ($image_pull_secrets) = &$get_deployer_conf($name,1,qw[image_pull_secrets]);
    +{image_pull_secrets=>$image_pull_secrets,%$options}
};

my $wrap_kc = sub{
    my($name,$tmp_path,$options) = @_;
    my $yml = &$make_kc_yml($name,$tmp_path,&$add_image_pull_secrets($name,$options));
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

my $get_auth = sub{ @{&$resolve("auth")->($_[0])||[]} };

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

my $get_simple_auth = sub{
    my($comp) = @_;
    my %auth = &$get_auth($comp);
    my $sk = $auth{"simple.auth"};
    return $sk if $sk ne '';
    my %d_auth = &$get_auth(&$get_deployer($comp));
    my $seed = &$mandatory_of("simple.seed"=>\%d_auth);
    return &$md5_hex("$seed$comp");
};

my $get_frp_items = sub{
    my($comp)=@_;
    &$map(&$get_compose($comp),sub{ my($k,$v)=@_;
        my $name =
            $k=~/^frpc:(\w+)$/ ? "$comp.$1" :
            $k=~/^frpc:(.+\.\w+)$/ ? $1 :
            return ();
        my ($host,$port) = $v=~/^(.+):(\d+)$/ ? ($1,$2) : die;
        [$name,$port,$host]
    });
};

my $get_frp_sk = sub{
    my($name)=@_;
    my $comp = $name=~/^(.*)\.\w+$/ ? $1 : die;
    &$get_simple_auth($comp);
};

my $get_frpc_conf = sub{
    my($comp) = @_;
    my @services = &$get_frp_items($comp);
    return "" if !@services;
    my @common = (common => [&$get_frp_common($comp)]);
    my @add = map{
        my($name,$port,$host) = @$_;
        ($name => [
            type => "stcp",
            sk => &$get_frp_sk($name),
            local_ip => $host,
            local_port => $port,
        ])
    } @services;
    &$to_ini_file([@common,@add]);
};
my $put_frpc_conf = sub{
    my($from_path,$content) = @_;
    my $put = &$rel_put_text($from_path);
    &$put("frpc.ini", $content);
};

my $get_visitor_conf = sub{
    my($comp,$services) = @_;
    my @common = (common => [&$get_frp_common($comp)]);
    my @add = map{
        my($port,$full_service_name) = @$_;
        ("$full_service_name.visitor" => [
            type => "stcp",
            role => "visitor",
            sk => &$get_frp_sk($full_service_name),
            server_name => $full_service_name,
            bind_port => $port,
            bind_addr => "0.0.0.0",
        ])
    } @$services;
    &$to_ini_file([@common,@add])
};

#C4INTERNAL_PORTS => "1080,1443",
#my $conf = &$to_ini_file([&$frp_web($$conf{proxy_dom}||$run_comp)]);

my $all_consumer_options = sub{(
    tty => "true",
    JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport ", # -XX:ActiveProcessorCount=36
    C4LOGBACK_XML => "/c4conf/logback.xml",
    C4AUTH_KEY_FILE => "/c4conf/simple.auth", #gate does no symlinks
)};

my $need_deploy_cert = sub{
    my($comp,$from_path)=@_;
    my $put = &$rel_put_text($from_path);
    &$put("simple.auth",&$get_simple_auth($comp));
};

my @req_small = (req_mem=>"100Mi",req_cpu=>"250m");
my @req_big = (req_mem=>"10Gi",req_cpu=>"1000m");

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
    my($run_comp,$img)=@_;
    my $conf = &$get_compose($run_comp);
    my $from_path = &$get_tmp_dir();
    &$make_secrets($run_comp,$from_path);
    ($from_path, {
        image => $img,
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
        &$all_consumer_options(),
        C4INBOX_TOPIC_PREFIX => ($prefix || die "no C4INBOX_TOPIC_PREFIX"),
        C4STORE_PASS_PATH    => "/c4conf-kafka-auth/kafka.store.auth",
        C4KEYSTORE_PATH      => "/c4conf-kafka-certs/kafka.keystore.jks",
        C4TRUSTSTORE_PATH    => "/c4conf-kafka-certs/kafka.truststore.jks",
        C4BOOTSTRAP_SERVERS  => ($bootstrap_servers || die "no host bootstrap_servers"),
        C4S3_CONF_DIR        => "/c4conf-ceph-client",
        C4HTTP_SERVER        => "http://$comp:$inner_http_port",
        C4ELECTOR_SERVERS    => join(",", map {"http://$elector-$_.$elector:$elector_port"} 0, 1, 2),
        C4READINESS_PATH     => "/c4/c4is-ready",
    )
};

my $need_ceph = sub{
    my ($comp,$from_path) = @_;
    my $kubectl = &$get_kubectl($comp);
    my $tmp_dir = &$get_tmp_dir();
    &$secret_to_dir($kubectl,"ceph-client",$tmp_dir);
    my $get = sub{ syf("cat $tmp_dir/$_[0]") };
    my $put = &$rel_put_text($from_path);
    my $conf = &$get_compose($comp);
    &$put("ceph.auth", join "&", map{"$$_[0]=$$_[1]"}
        [url=>&$get("address")],
        [id=>&$get("key")],
        [pass=>&$get("secret")],
        [bucket=>&$mandatory_of("C4INBOX_TOPIC_PREFIX"=>$conf)]
    );
};

my $up_consumer = sub{
    my($run_comp,$img)=@_;
    my $conf = &$get_compose($run_comp);
    my $gate_comp = $$conf{ca} || die "no ca";
    my %consumer_options = &$get_consumer_options($gate_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($gate_comp,$from_path);
    &$need_ceph($gate_comp,$from_path);
    &$make_secrets($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    ($from_path, { image => $img, %consumer_options, @req_big, %$conf });
};
my $up_gate = sub{
    my($run_comp,$img)=@_;
    my %consumer_options = &$get_consumer_options($run_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    my $hostname = &$get_hostname($run_comp) || die "no le_hostname";
    my ($ingress_secret_name) = &$get_deployer_conf($run_comp,0,qw[ingress_secret_name]);
    my $conf = &$get_compose($run_comp);
    ($from_path, {
        image => $img, %consumer_options,
        C4STATE_TOPIC_PREFIX => "gate",
        C4STATE_REFRESH_SECONDS => 1000,
        req_mem => "4Gi", req_cpu => "1000m",
        "port:$inner_http_port:$inner_http_port"=>"",
        #"port:$inner_sse_port:$inner_sse_port"=>"",
        "ingress:$hostname/"=>$inner_http_port,
        #"ingress:$hostname/sse"=>$inner_sse_port,
        ingress_secret_name=>$ingress_secret_name,
        C4HTTP_PORT => $inner_http_port,
        C4SSE_PORT => $inner_sse_port,
        need_pod_ip => 1,
        (map{($_=>&$mandatory_of($_=>$conf))} qw[C4KEEP_SNAPSHOTS replicas]),
    });
};

my $up_frp_client = sub{
    my($comp,$img)=@_;
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    ($from_path,{
        image => $img, C4FRPC_INI => "/c4conf/frpc.ini", @req_small,
    })
};

my $installer_steps = sub{(
    "COPY install.pl /",
    "RUN perl install.pl useradd",
)};

my $base_image_steps = sub{(
    "FROM ubuntu:20.04",
    &$installer_steps(),
)};

my $dl_node_url = "https://nodejs.org/dist/v14.15.4/node-v14.15.4-linux-x64.tar.xz";

my $dl_frp_url = "https://github.com/fatedier/frp/releases/download/v0.21.0/frp_0.21.0_linux_amd64.tar.gz";
my $make_frp_image_inner = sub{
    my ($v) = @_; ## $v -- just to have different images with newer c-time and prevent pruning
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
        'ENTRYPOINT ["perl", "/frp.pl", "'.$v.'"]',
    );
    return $from_path;
};

# m  h g b z -- m-h m-b  h-g g-b b-z l-h l-g l-b l-z
# dc:
# kc:

push @tasks, ["ci_up-consumer", "", $up_consumer];
push @tasks, ["ci_up-gate", "", $up_gate];
push @tasks, ["ci_up-client", "", $up_client];
push @tasks, ["ci_up-frp_client", "", $up_frp_client];

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

my $snapshot_get_statements = sub{
    my($gate_comp)=@_;
    my $prefix = &$get_compose($gate_comp)->{C4INBOX_TOPIC_PREFIX}
        || die "no C4INBOX_TOPIC_PREFIX for $gate_comp";
    my ($client_comp) = &$get_deployer_conf($gate_comp,1,qw[s3client]);
    my ($pod) = &$get_pods($client_comp); # any is ok
    my $stm = sub{
        my($op,$fn) = @_;
        &$kj_exec($client_comp,$pod,"","/tools/mc $op def/$prefix.snapshots/$fn")
    };
    my $cat = sub{
        my($from,$to)=@_;
        $from && $to || die;
        &$stm("cat",$from)." > $to"
    };
    (&$stm("ls",""), $cat)
};

my $snapshot_parse_last = sub{
    my($data)=@_;
    (sort{$b cmp $a} grep{ &$snapshot_name($_) } $data=~/(\S+)/g)[0];
};

push @tasks, ["snapshot_get", "$composes_txt [|snapshot|last]", sub{
    my($gate_comp,$arg)=@_;
    &$ssh_add();
    my ($ls_stm,$cat) = &$snapshot_get_statements($gate_comp);
    if(!defined $arg){
        sy($ls_stm);
    } else {
        my $snnm = $arg ne "last" ? $arg : &$snapshot_parse_last(syf($ls_stm));
        my $fn = &$snapshot_name($snnm) || die "bad or no snapshot name";
        sy(&$cat(@$fn));
    }
}];

my $snapshot_put = sub{
    my($auth_path,$data_path,$addr)=@_;
    my $gen_dir = &$get_proto_dir();
    my $data_fn = $data_path=~m{([^/]+)$} ? $1 : die "bad file path";
    -e $auth_path or die "no gate auth";
    ("python3","$gen_dir/req.py",$auth_path,$data_path,$addr,"/put-snapshot","/put-snapshot","snapshots/$data_fn");
};

my $need_auth_path = sub{
    my($comp)=@_;
    #my %consumer_options = &$get_consumer_options($comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($comp,$from_path);
    "$from_path/simple.auth"
};

push @tasks, ["snapshot_put", "$composes_txt <file_path|nil> [to_address]", sub{
    my($comp,$data_path_arg,$address_arg)=@_;
    &$ssh_add();
    my $host = &$get_hostname($comp);
    my $address = $address_arg || $host && "https://$host" ||
        die "need le_hostname or domain_zone for $comp or address";
    my $data_path = $data_path_arg ne "nil" ? $data_path_arg :
        &$put_temp("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e","");
    sy(&$snapshot_put(&$need_auth_path($comp),$data_path,$address));
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
push @tasks, ["log","[pod|$composes_txt] [tail] [add]",sub{
    my($arg_opt,$tail,$add)=@_;
    my $arg = $arg_opt || &$mandatory_of(C4INBOX_TOPIC_PREFIX=>\%ENV)."-main";
    &$ssh_add();
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        my $tail_or = ($tail+0) || 100;
        sy(qq[$kubectl logs -f $pod --tail $tail_or $add]);
    });
}];
push @tasks, ["log_debug","<pod|$composes_txt> [class]",sub{ # ee.cone
    my($arg,$cl)=@_;
    &$ssh_add();
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        if($cl){
            my $content = qq[<logger name="$cl" level="DEBUG"><appender-ref ref="ASYNCFILE" /></logger>];
            sy(qq[$kubectl exec -i $pod -- sh -c 'cat >> /tmp/logback.xml' < ].&$put_temp("logback.xml",$content));
        } else {
            so(qq[$kubectl exec -i $pod -- rm /tmp/logback.xml]);
        }
    });
}];

#################

push @tasks, ["builder_cleanup"," ",sub{
    &$ssh_add();
    my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
    my @ssh = &$ssh_ctl($builder_comp);
    do {
        my @to_kill = map {/^c4\s+(\d+).+\bdocker\s+build\b/ ? "$1" : ()}
            syl(join " ", @ssh, "ps", "-ef");
        @to_kill and sy(@ssh, "kill", @to_kill);
    };
    sy(@ssh,"test -e $tmp_root && rm -r $tmp_root; true");
    do{
        my @to_rm = map{/^(\S+)\s+(\S+\.base-opt\.\w+)\s/ ?"$1:$2":()}
            syl(&$remote($builder_comp,"docker images"));
        @to_rm and sy(@ssh,"docker","rmi",@to_rm);
    };
}];

my $get_rand_uuid = sub{ syf("uuidgen")=~/(\S+)/ ? "$1" : die };
my $ci_get_remote_dir = sub{ "$tmp_root/job-".&$get_rand_uuid()."-$_[0]" };

my $ci_measure = sub{
    my $at = time;
    sub{ print((time-$at)."s =measured= $_[0]\n") }
};

my $ci_docker_build = sub {
    my ($local_dir, $builder_comp, $img) = @_;
    my $end = &$ci_measure();
    my $remote_dir = &$ci_get_remote_dir("context");
    &$rsync_to($local_dir, $builder_comp, $remote_dir);
    sy(&$ssh_ctl($builder_comp, "-t", "docker", "build", "-t", $img, $remote_dir));
    sy(&$ssh_ctl($builder_comp, "rm", "-r", $remote_dir));
    &$end("ci built $img");
    $img;
};

my $ci_docker_build_result = sub{
    my ($builder_comp, $builder_img, $runtime_img) = @_;
    my $end = &$ci_measure();
    my $remote_dir = &$ci_get_remote_dir("context");
    my $container_name = "c4cp-".&$get_rand_uuid();
    sy(&$ssh_ctl($builder_comp,@$_)) for (
        ["docker","create","--name",$container_name,$builder_img],
        ["docker","cp","$container_name:/c4/res",$remote_dir], #do not mkdir before
        ["docker","rm","-f",$container_name],
        ["docker","build","-t",$runtime_img,$remote_dir],
        ["rm","-r",$remote_dir],
    );
    &$end("ci built $runtime_img");
};

my $make_dir_with_dockerfile = sub{
    my($steps)=@_;
    my $dir = &$get_tmp_dir();
    &$put_text("$dir/Dockerfile",$steps);
    $dir;
};

my $ci_docker_push = sub{
    my($kubectl,$builder_comp,$add_path,$images)=@_;
    my $end = &$ci_measure();
    my $remote_dir = &$ci_get_remote_dir("config");
    my $local_dir = &$get_tmp_dir();
    &$secret_to_dir($kubectl,"docker",$local_dir);
    my $path = "$local_dir/config.json";
    &$put_text($path,&$encode(&$merge(map{&$decode(syf("cat $_"))} $path, $add_path)));
    &$rsync_to($local_dir,$builder_comp,$remote_dir);
    my @config_args = ("--config"=>$remote_dir);
    my @tasks = map{[&$ssh_ctl($builder_comp,"-t","docker",@config_args,"push",$_)]} @$images;
    for my $task(@tasks){
        sy(@$task);
    }
    sy(&$ssh_ctl($builder_comp,"rm","-r",$remote_dir));
    &$end("ci pushed");
};

my $mem_repo_commits = sub{
    my($dir)=@_;
    my $content = join " ", sort map{
        my $commit =
            syf("git --git-dir=$_ rev-parse --short HEAD")=~/(\S+)/ ? $1 : die;
        my $l_dir = m{^\./(|.*/)\.git$} ? $1 : die;
        "$l_dir:$commit";
    } syf("cd $dir && find -name .git")=~/(\S+)/g;
    &$put_text(&$need_path("$dir/target/c4repo_commits"),$content);
};

my $ci_de_step = "ENTRYPOINT exec perl \$C4CI_PROTO_DIR/sandbox.pl main";

push @tasks, ["ci_build_common", "", sub{
    my $end = &$ci_measure();
    &$ssh_add();
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR=>\%ENV);
    my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    my $deploy_context = &$mandatory_of(C4DEPLOY_CONTEXT=>\%ENV);
    my $docker_conf_path = &$mandatory_of(C4CI_DOCKER_CONFIG=>\%ENV);
    sy("cp $local_dir/build.def.dockerfile $local_dir/Dockerfile");
    sy("cp $proto_dir/.dockerignore $local_dir/") if $local_dir ne $proto_dir;
    &$mem_repo_commits($local_dir);
    &$ci_docker_build($local_dir,$builder_comp,$common_img);
    my $kubectl = &$get_kubectl_raw($deploy_context);
    &$ci_docker_push($kubectl,$builder_comp,$docker_conf_path,[$common_img]);
    my $dir = &$make_dir_with_dockerfile(join"\n","FROM $common_img",$ci_de_step);
    &$ci_docker_build($dir, $builder_comp, "$common_img.nil-def-main.de");
    &$end("ci_build_common");
}];

my $ci_build = sub{
    my($mode,@args) = @_;
    my $end = &$ci_measure();
    &$ssh_add();
    my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    #
    my $build_derived = sub{
        my ($from,$steps,$img) =@_;
        my $dir = &$make_dir_with_dockerfile(join"\n","FROM $from",@$steps);
        &$ci_docker_build($dir, $builder_comp, $img);
    };
    my $get_build_steps = sub{
        my($can_fail,$proj_tag)=@_;
        (
            "ENV C4CI_CAN_FAIL=$can_fail",
            "ENV C4CI_BASE_TAG_ENV=$proj_tag",
            "RUN eval \$C4STEP_BUILD",
            "RUN eval \$C4STEP_BUILD_CLIENT"
        )
    };
    my $chk_tag = sub{
        $_[0]=~/^(\w[\w\-]*\w)$/ ? "$1" : die "bad image postfix ($_[0])"
    };
    my $handle_aggr = sub{
        my($aggr_tag_arg,$proj_tags)=@_;
        my $aggr_tag = &$chk_tag($aggr_tag_arg);
        my $steps = [&$get_build_steps("1",$aggr_tag)];
        my $aggr_img = "$common_img.$aggr_tag.aggr";
        &$build_derived($common_img,$steps,$aggr_img);
        for($proj_tags=~/([^:]+)/g){
            my $proj_tag = &$chk_tag($_);
            my $sb_steps = [
                "ENV C4CI_BASE_TAG_ENV=$proj_tag",
                "ENTRYPOINT exec perl \$C4CI_PROTO_DIR/sandbox.pl main",
            ];
            &$build_derived($aggr_img,$sb_steps,"$common_img.$proj_tag.de");
        }
    };
    my $handle_fin = sub{
        my($proj_tag,$aggr_tag) = map{&$chk_tag($_)} @_;
        my $from_img = $aggr_tag ? "$common_img.$aggr_tag.aggr" : $common_img;
        my $img_pre = "$common_img.$proj_tag";
        my $cp_steps = [
            &$get_build_steps("",$proj_tag),
            "RUN \$C4STEP_CP"
        ];
        &$build_derived($from_img,$cp_steps,"$img_pre.cp");
        &$ci_docker_build_result($builder_comp,"$img_pre.cp","$img_pre.rt");
    };
    my %handle = (aggr=>$handle_aggr,fin=>$handle_fin);
    &{$handle{$mode}}(@args);
    &$end("ci_build");
};

push @tasks, ["ci_build_aggr", "", sub{ &$ci_build("aggr",@_) }];
push @tasks, ["ci_build", "", sub{ &$ci_build("fin",@_) }];

push @tasks, ["ci_build_frp", "", sub{
    &$ssh_add();
    my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    my $dir = &$make_frp_image_inner($common_img);
    my $img = "$common_img.frp.rt";
    &$ci_docker_build($dir, $builder_comp, $img);
}];

my $get_existing_images = sub{
    my($builder_comp,$builder_repo)=@_;
    map{/^(\S+)\s+(\S+)/ && $1 eq $builder_repo ?"$1:$2":()}
        syl(&$remote($builder_comp,"docker images"));
};

my $ci_docker_tag = sub{
    my ($builder_comp,@args) = @_;
    sy(&$ssh_ctl($builder_comp,"docker","tag",@args));
};

my @kinds = (
    [qw[core v1 Secret]],[qw[core v1 Service]],
    [qw[apps v1 Deployment]],[qw[apps v1 StatefulSet]],
    [qw[extensions v1beta1 Ingress]],
);

my $ci_get_image = sub{
    my($common_img,$comp) = @_;
    my $conf = &$get_compose($comp);
    my $image_type = $$conf{image_type} || "rt";
    my $proj_tag = &$mandatory_of(project=>$conf);
    my $img = "$common_img.$proj_tag.$image_type";
    my($prod_repo,$allow_source_repo) =
        &$get_deployer_conf($comp,0,qw[sys_image_repo allow_source_repo]);
    $allow_source_repo ? ($img,$img) :
        $prod_repo && $img=~/:([^:]+)$/ ? ($img,"$prod_repo:$1") :
            die "source deploy to alien environment was denied";
};

my $ci_get_compositions = sub{
    my($env_comp) = @_;
    my @comps = &$spaced_list(&$get_compose($env_comp)->{parts}||[$env_comp]);
    &$get_deployer($env_comp) eq &$get_deployer($_) || die "deployers do not match" for @comps;
    @comps
};

my $ci_get_attributes = sub{
    my($conf)=@_;
    &$map($conf,sub{ my($k,$v)=@_; $k=~/^ci:(.*)$/ ? ($1=>$v) : () });
};

push @tasks, ["ci_info", "", sub{
    my($env_comp,$out_path)=@_;
    &$ssh_add();
    my $conf = &$get_compose($env_comp);
    my %out = &$ci_get_attributes($conf);
    my @comps = &$ci_get_compositions($env_comp);
    my @parts = map{
        my %res = &$ci_get_attributes(&$get_compose($_));
        %res ? \%res : ()
    } @comps;
    &$put_text(($out_path||die), &$encode({%out,ci_parts=>\@parts}));
}];

push @tasks, ["ci_push", "", sub{
    my($env_comp)=@_;
    &$ssh_add();
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    my $builder_comp = &$mandatory_of(C4CI_BUILDER=>\%ENV);
    my $docker_conf_path = &$mandatory_of(C4CI_DOCKER_CONFIG=>\%ENV);
    my $deploy_context = &$mandatory_of(C4DEPLOY_CONTEXT=>\%ENV);
    my $builder_repo = $common_img=~/^(.+):[^:]+$/ ? $1 : die;
    my @comps = &$ci_get_compositions($env_comp);
    my @existing_images = &$get_existing_images($builder_comp,$builder_repo);
    my %existing_images = map{($_=>1)} @existing_images;
    for my $part_comp(@comps){
        my($from_img,$to_img) = &$ci_get_image($common_img,$part_comp);
        if($existing_images{$from_img}) {
            &$ci_docker_tag($builder_comp, $from_img, $to_img) if $from_img ne $to_img;
            my $kubectl = &$get_kubectl_raw($deploy_context);
            &$ci_docker_push($kubectl, $builder_comp, $docker_conf_path, [ $to_img ]);
        } else {
            print "image does not exist ($from_img) => ($to_img)\n";
        }
    }
}];

my $ci_env_name = sub{
    my($env_comp)=@_;
    my $env_name = &$mandatory_of("ci:env"=>&$get_compose($env_comp));
    my $kubectl = &$get_kubectl($env_comp);
    ($kubectl,$env_name)
};

my $ci_apply = sub{
    my ($kubectl,$env_name,$tmp) = @_;
    my $whitelist = join " ", map{"--prune-whitelist $$_[0]/$$_[1]/$$_[2]"} @kinds;
    sy("$kubectl apply -f $tmp --prune -l c4env=$env_name $whitelist");
};

push @tasks, ["ci_up", "", sub{
    my($env_comp)=@_;
    my $end = &$ci_measure();
    &$ssh_add();
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    my @comps = &$ci_get_compositions($env_comp);
    my ($kubectl,$env_name) = &$ci_env_name($env_comp);
    my $labeled = {metadata=>{labels=>{c4env=>$env_name}}};
    my $yml_str = join "\n", map{ &$encode(&$merge_list($_,$labeled)) } map{
        my $l_comp = $_;
        my($from_img,$to_img) = &$ci_get_image($common_img,$l_comp);
        &$ignore($from_img);
        my ($tmp_path,$options) = &$find_handler(ci_up=>$l_comp)->($l_comp,$to_img);
        @{&$make_kc_yml($l_comp,$tmp_path,&$add_image_pull_secrets($l_comp,$options))};
    } @comps;
    #
    my $secret_name = "hist-".&$get_rand_uuid();
    my $hist_yml = &$put_temp("hist.yml",&$encode(&$merge_list(
        &$secret_yml_from_files($secret_name, {value=>&$put_temp("value",$yml_str)}),
        {metadata=>{
            labels=>{"c4target-env"=>$env_name}, annotations=>{c4img=>$common_img},
        }},
    )));
    sy("$kubectl apply -f $hist_yml");
    &$ci_apply($kubectl,$env_name,&$put_temp("up.yml",$yml_str));
    &$end("ci_up");
}];

my $get_image_from_deployment = sub{ #.spec.template.spec.containers[*].image
    my ($deployment) = @_;
    my ($curr_img,@more) = map{$$_{image}} map{@$_} map{$$_{containers}||{}}
        map{$$_{spec}||{}} map{$$_{template}||{}} map{$$_{spec}||{}} $deployment;
    die if @more;
    $curr_img
};

push @tasks, ["hist_ls","<env-comp>", sub{
    my($env_comp)=@_;
    &$ssh_add();
    my ($kubectl,$env_name) = &$ci_env_name($env_comp);
    my $deploy_res = syf("$kubectl get deployment -l c4env=$env_name -o json");
    my $hist_res = syf("$kubectl get secrets -l c4target-env=$env_name -o json");
    print "Current deployment images:\n";
    print "\t".&$get_image_from_deployment($_)."\n"
        for @{&$decode($deploy_res)->{items}||die};
    print "History:\n";
    print for sort
        map{"\t$$_{creationTimestamp} $$_{name} $$_{annotations}{c4img}\n"}
        map{$$_{metadata}} @{&$decode($hist_res)->{items}||die};
}];

push @tasks, ["hist_revert","<env-comp> <hist-item>", sub{
    my($env_comp,$hist_item)=@_;
    &$ssh_add();
    my ($kubectl,$env_name) = &$ci_env_name($env_comp);
    my $tmp_dir = &$get_tmp_dir();
    &$secret_to_dir($kubectl,$hist_item,$tmp_dir);
    &$ci_apply($kubectl,$env_name,"$tmp_dir/value");
}];

push @tasks, ["ci_down","",sub{
    my($env_comp)=@_;
    &$ssh_add();
    my ($kubectl,$env_name) = &$ci_env_name($env_comp);
    my $kinds = join ",",map{$$_[2]}@kinds;
    sy("$kubectl delete -l c4env=$env_name $kinds");
}];

my $ci_wait = sub{
    my @comps = @_;
    my $end = &$ci_measure();
    for my $comp(@comps){
        my $host = &$get_hostname($comp) || next;
        sleep 1 while so("curl -f https://$host/availability");
    }
    &$end("ci wait");
};

my $ci_parallel = sub{
    my @task_stm_list = @_;
    my $end = &$ci_measure();
    my $gen_dir = &$get_proto_dir();
    sy("python3 $gen_dir/parallel.py 4 < ".&$put_temp("tasks",join "",map{"$_\n"}@task_stm_list));
    &$end("ci parallel");
};

push @tasks, ["ci_setup", "", sub{
    my($env_comp)=@_;
    my $end = &$ci_measure();
    &$ssh_add();
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my @comps = &$ci_get_compositions($env_comp);
    my %branch_conf = map{%{&$decode(syf("cat $_"))}} grep{-e} "$local_dir/branch.conf.json";
    my $snapshot_from_key = $branch_conf{ci_snapshot_from};
    my @from_to_comps = map{
        my $from = $snapshot_from_key && &$get_compose($_)->{"snapshot_from:$snapshot_from_key"};
        $from ? [$from,$_] : ()
    } @comps;
    my $to_by_from_comp = &$merge_list({}, map{
        my($from,$to) = @$_;
        +{$from=>[$to]}
    } @from_to_comps);
    my @to_comps = map{$$_[1]}@from_to_comps;
    my $tasks = &$merge_list({},&$map($to_by_from_comp,sub{ my($from_comp,$to_comp_list)=@_;
        my ($ls_stm,$cat) = &$snapshot_get_statements($from_comp);
        my $fn = &$snapshot_name(&$snapshot_parse_last(syf($ls_stm))) || die "bad or no snapshot name";
        my ($from_fn,$to_fn) = @$fn;
        my @put_tasks = map{"$_ || $_ || $_"} map{ my $to_comp = $_;
            my $host = &$get_hostname($to_comp) || die;
            my $address = "https://$host";
            join " ", &$snapshot_put(&$need_auth_path($to_comp),$to_fn,$address);
        } @$to_comp_list;
        +{
            get => { $to_fn => [&$cat($from_fn,$to_fn)] },
            put => \@put_tasks,
        }
    }));
    &$ci_parallel(sort map{$$_[0]} values %{$$tasks{get}||{}});
    &$ci_wait(@to_comps);
    &$ci_parallel(@{$$tasks{put}||[]});
    &$ci_wait(@to_comps);
    &$end("ci_setup");
}];

push @tasks, ["ci_check_images", "", sub {
    my ($env_comp) = @_;
    &$ssh_add();
    my $common_img = &$mandatory_of(C4COMMON_IMAGE=>\%ENV);
    my @comps = &$ci_get_compositions($env_comp);
    my $kubectl = &$get_kubectl($env_comp);
    for my $comp(@comps){
        while(1){
            my $stm = qq[$kubectl get deployment -o jsonpath="{.items[*].metadata.name}"];
            last if grep{$_ eq $comp} &$spaced_list(syf($stm));
            sleep 2;
        }
        my ($from_img, $to_img) = &$ci_get_image($common_img, $comp);
        print "target image:     $to_img\n";
        while(1){
            my $resp = &$decode(syf(qq[$kubectl get deployment $comp -o json]));
            my $curr_img = &$get_image_from_deployment($resp);
            print "deployment image: $curr_img\n";
            last if $curr_img eq $to_img;
            sleep 2;
        }
        while(1){
            my $resp = &$decode(syf("$kubectl get po -l app=$comp -ojson"));
            my @statuses = map{@$_} map{$$_{containerStatuses}||[]}
                map{$$_{status}||{}} map{@$_} map{$$_{items}||[]} $resp;
            if(@statuses){
                my @others = grep{$_ ne $to_img} map{$$_{image}} @statuses;
                print "other images: $_\n" for @others;
                my @not_ready = map{$$_{ready}?():$$_{state}} @statuses;
                print "not ready: ".&$encode($_)."\n" for @not_ready;
                @others or @not_ready or last;
            }
            sleep 2;
        }
    }
}];

push @tasks, ["ci_check", "", sub{
    my($env_comp)=@_;
    &$ssh_add();
    my @comps = &$ci_get_compositions($env_comp);
    my $kubectl = &$get_kubectl($env_comp);
    sy("$kubectl rollout status deployments/$_") for @comps;
}];

########

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
    $mode eq "fast" ? "--color --env.fast=true --mode development" :
    $mode eq "dev" ? "--color --mode development" :
    "--color --mode production";
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
        and sy("cd $dir && npm install --no-save");
    sy("cd $dir && cp $conf_dir/webpack.config.js . && cp $conf_dir/tsconfig.json . && node_modules/webpack/bin/webpack.js $opt");# -d
    &$put_text("$build_dir/publish_time",time);
    &$put_text("$build_dir/c4gen.ht.links",join"",
        map{ my $u = m"^/(.+)$"?$1:die; "base_lib.ee.cone.c4gate /$u $u\n" }
        map{ substr $_, length $build_dir }
        sort <$build_dir/*>
    );
};

push @tasks, ["build_client","",sub{
    my($dir,$mode)=@_;#abs dir
    &$build_client($dir, &$client_mode_to_opt($mode));
}];
push @tasks, ["build_client_changed","",sub{
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
    my @add_steps = syl("cat $gen_dir/.bloop/c4/tag.$base.steps");
    my @from_steps = grep{/^FROM\s/} @add_steps;
    &$put_text("$ctx_dir/Dockerfile", join "\n",
        @from_steps ? @from_steps : "FROM ubuntu:18.04",
        &$installer_steps(),
        "RUN perl install.pl apt".
        " curl software-properties-common".
        " lsof mc iputils-ping netcat-openbsd fontconfig".
        " openssh-client", #repl
        "RUN perl install.pl curl https://github.com/AdoptOpenJDK/openjdk15-binaries/releases/download/jdk-15.0.1%2B9/OpenJDK15U-jdk_x64_linux_hotspot_15.0.1_9.tar.gz",
        #"RUN perl install.pl curl https://download.bell-sw.com/java/17.0.2+9/bellsoft-jdk17.0.2+9-linux-amd64.tar.gz",
        'ENV PATH=${PATH}:/tools/jdk/bin',
        (grep{/^RUN\s/} @add_steps),
        "ENV JAVA_HOME=/tools/jdk",
        "RUN chown -R c4:c4 /c4",
        "WORKDIR /c4",
        "RUN ln -s /c4/greys /tools/greys",
        "USER c4",
        "COPY --chown=c4:c4 . /c4",
        "RUN cd /tools/greys && bash ./install-local.sh",
        'ENTRYPOINT ["perl","run.pl"]',
    );
    sy("cp $proto_dir/$_ $ctx_dir/$_") for "install.pl", "run.pl";
    sy("cd $ctx_dir && tar -xzf $proto_dir/tools/greys.tar.gz");
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
    &$put_text("$ctx_dir/serve.sh", join "\n",
        "export C4APP_CLASS=ee.cone.c4actor.ParentElectorClientApp",
        "export C4APP_CLASS_INNER=$main_cl",
        "exec java ee.cone.c4actor.ServerMain"
    );
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
# push @tasks, ["ci_rm","",sub{ #to call from Dockerfile
#     my ($base,$gen_dir,$proto_dir) = &$ci_inner_opt();
#     &$ignore($base,$proto_dir);
#     print "[purging]\n";
#     for(sort{$b cmp $a} syl("cat $gen_dir/ci.rm")){ chomp $_ or die "[$_]"; unlink $_; rmdir $_ }
# }];

my $make_frp_image = sub{
    my ($comp) = @_;
    return &$remote_build(''=>$comp,&$make_frp_image_inner(""));
};

push @tasks, ["up-frp_client", "", sub{
    my ($comp) = @_;
    &$wrap_deploy($comp,&$up_frp_client($comp, &$make_frp_image($comp)));
}];

push @tasks, ["up-visitor", "", sub{
    my ($comp) = @_;
    my $conf = &$get_compose($comp);
    my $img = &$make_frp_image($comp);
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
    my $visitor_conf = &$get_visitor_conf($comp,[@visits]);
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    &$put("frpc.visitor.ini", $visitor_conf);
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
                apiGroups => ["","apps","extensions"],
                resources => ["statefulsets","secrets","services","deployments","ingresses"],
                verbs => ["get","create","patch","delete","list"],
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

push @tasks, ["up-frps","",sub{
    my($comp)=@_;
    my $img = &$make_frp_image($comp);
    my %auth = &$get_auth($comp);
    my $token = $auth{frps_token} || die "no frps_token in $comp";
    my $conf_content = &$to_ini_file([common=>[
        token=>$token,
        dashboard_addr => "0.0.0.0",
        dashboard_port => "7500",
        dashboard_user => "cone",
        dashboard_pwd => $token,
    ]]);
    my $options = {
        image => $img,
        C4FRPS_INI => "/c4conf/frps.ini",
        "port:7000:7000" => "",
        "port:7500:7500" => "",
        @req_small,
    };
    my $from_path = &$get_tmp_dir();
    my $put = &$rel_put_text($from_path);
    &$put("frps.ini", $conf_content);
    &$wrap_deploy($comp,$from_path,$options);
}];

####

my $tp_split = sub{ "$_[0]\n\n"=~/(.*?\n\n)/gs };
my $sleep = sub{ select undef, undef, undef, $_[0] };
push @tasks, ["thread_print","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    my @cmd = sort{$b<=>$a} map{
        my $pod = $_;
        map{
            my $pid = /^(\d+)\s+(ee\.cone\.\S+)/  ?"$1":();
            &$kj_exec($comp,$pod,"","jcmd $pid Thread.print")
        } syl(&$kj_exec($comp,$pod,"","jcmd"));
    } &$get_pods($comp);
    while(@cmd){
        &$sleep(0.25);
        print grep{ !/\.epollWait\(/ && /\sat\s/ } &$tp_split(syf($_)) for @cmd;
    }
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
    if(!-e "$ENV{HOME}/.greys"){
        my $gen_dir = &$get_proto_dir();
        sy("cd $ENV{HOME} && tar -xzf $gen_dir/tools/greys.tar.gz");
        sy("cd $ENV{HOME}/greys && bash ./install-local.sh");
    }
    sy("$ENV{HOME}/greys/greys.sh $pid");
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
    my @services =
        map{ my($name,$port,$host) = @$_; &$ignore($host); [$port=>$name] }
            &$get_frp_items($comp);
    print &$get_visitor_conf($comp,[@services]);
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
    my %data = map{ (substr($_, 1+length $dir)=>$_) } sort <$dir/*>;
    my $secret = &$encode(&$secret_yml_from_files($secret_name, \%data));
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

push @tasks, ["secret_add_arg","$composes_txt <secret-content>",sub{
    my($comp,$secret_content)=@_;
    &$ssh_add();
    my $kubectl = &$get_kubectl($comp);
    my $hash = &$md5_hex($secret_content);
    my $secret_name = "c4hash-$hash";
    my $dir = &$get_tmp_dir();
    my $put = &$rel_put_text($dir);
    my $fn = "value";
    &$put($fn,$secret_content);
    &$dir_to_secret($kubectl,$secret_name,$dir);
    print qq[ADD TO CONFIG: "/c4conf-$secret_name/$fn"\n];
}];

my $restart = sub{
    my $local_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    &$put_text(&$need_path("$local_dir/target/gen-ver"),time);
};

push @tasks, ["debug","<on|off> [components]",sub{
    my($arg,$obj)=@_;
    my $d_path = $obj eq "" ? "/c4/debug-enable" :
        $obj eq "components" ? "/c4/debug-components" : die;
    if($arg eq "on"){
        -e $d_path or &$put_text($d_path,"");
    }elsif($arg eq "off"){
        -e $d_path and sy("rm $d_path");
    }else{ die }
    &$restart();
}];

push @tasks, ["tag","[tag]",sub{
    my($tag)=@_;
    &$put_text("/c4/debug-tag",$tag||"");
    &$restart();
}];

push @tasks, ["kafka","( topics | offsets <hours> | nodes | sizes <node> | topics_rm )",sub{
    my @args = @_;
    my $gen_dir = &$get_proto_dir();
    my $cp = syf("coursier fetch --classpath org.apache.kafka:kafka-clients:2.8.0")=~/(\S+)/ ? $1 : die;
    sy("CLASSPATH=$cp java --source 15 $gen_dir/kafka_info.java ".join" ",@args);
}];
push @tasks, ["kafka_purge"," ",sub{
    my $gen_dir = &$get_proto_dir();
    sy("python3.8","$gen_dir/kafka_purger.py")
}];

####

&$main(@ARGV);
&$cleanup();
