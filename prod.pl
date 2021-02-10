#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

my $sys_image_ver = "v85";

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

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

my $get_conf_cert_path = lazy{
    my $path = $ENV{C4DEPLOY_CONF} || die "no C4DEPLOY_CONF";
    my $dir = &$get_tmp_dir();
    sy("cd $dir && tar -xzf $path");
    "$dir/id_rsa";
};

my $ssh_add  = sub{ "ssh-add ".&$get_conf_cert_path() };

my $parse_deploy_location = sub{
    $_[0]=~m{^([\w+\.\-]+):(\d+)(/[\w+\.\-/]+)$} ?
        ("$1","$2","$3") : die "bad C4DEPLOY_LOCATION";
};

my $get_conf_dir = lazy{
    my($host,$port,$path) = &$parse_deploy_location($ENV{C4DEPLOY_LOCATION});
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
    my ($host,$port,$user) = &$get_host_port($comp);
    sy(&$remote($comp,"mkdir -p $to_path"));
    sy("rsync -e 'ssh -p $port' -a --del --no-group $from_path/ $user\@$host:$to_path");
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

my $get_proto_dir = sub{ $ENV{C4PROTO_DIR} || $ENV{C4CI_PROTO_DIR} || die 'no C4PROTO_DIR' };

my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
};

####

push @tasks, ["","",sub{
    print join '', map{"$_\n"} "usage:",
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
}];

push @tasks, ["edit_auth"," ",sub{ #?todo locking or merging
    sy(&$ssh_add());
    my($dir,$save) = @{&$get_conf_dir()};
    sy("mcedit","$dir/auth.pl");
    sy($save);
}];

push @tasks, ["stack_list"," ",sub{
    sy(&$ssh_add());
    my $width = 6;
    my $composes = &$get_deploy_conf() || die;
    print join '', map{"$_\n"} (map{
        my $conf = $$composes{$_} || die;
        my $description = $$conf{description} ? $$conf{description} :
            $$conf{deployer} ? "$$conf{type} at $$conf{deployer}" : $$conf{type};
        "  $_".(" "x($width-length))." -- $description"
    } sort keys %$composes);

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

push @tasks, ["pipe", "$composes_txt <from_remote_cmd> <to_local_cmd>", sub{
    my($comp,$from_remote_cmd,$to_local_cmd)=@_;
    sy(&$ssh_add());
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

my $get_kc_ns = sub{
    my($comp)=@_;
    my $ns = syf(&$remote($comp,'cat /var/run/secrets/kubernetes.io/serviceaccount/namespace'))=~/(\w+)/ ? "$1" : die;
};
my $get_pods = sub{
    my($comp,$ns)=@_;
    my $stm = qq[kubectl -n $ns get po -l app=$comp -o jsonpath="{.items[*].metadata.name}"];
    syf(&$remote($comp,$stm))=~/(\S+)/g;
};
my $get_pod = sub{
    my($comp,$ns)=@_;
    my @pods = &$get_pods($comp,$ns);
    my $pod = &$single_or_undef(@pods) ||
        &$single_or_undef(grep{
            my $pod = $_;
            0 < grep{/^c4is-master\s*$/} syl(&$remote($comp,qq[kubectl -n $ns exec $pod -- ls /c4])) #todo: with elector2 it'll be pods?
        }@pods) ||
        die "no single pod for $comp";
    ($ns,$pod)
};

my $exec_stm_dc = sub{
    my($md,$comp,$service,$stm)=@_;
    qq[docker exec $md $comp\_$service\_1 sh -c "JAVA_TOOL_OPTIONS= $stm"];
};
my $exec_stm_kc = sub{
    my($ns,$md,$pod,$service,$stm) = @_;
    qq[kubectl -n $ns exec $md $pod -c $service -- sh -c "JAVA_TOOL_OPTIONS= $stm"];
};
my $exec_dc = sub{
    my($comp)=@_;
    sub{ my($md,$service,$stm)=@_; &$exec_stm_dc($md,$comp,$service,$stm) };
};
my $exec_kc = sub{
    my($comp)=@_;
    my $ns = &$get_kc_ns($comp);
    my $pod = &$get_pod($comp,$ns);
    sub{ my($md,$service,$stm)=@_; &$exec_stm_kc($ns,$md,$pod,$service,$stm) };
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
    my $pod = &$get_pod($comp,$ns);
    my $stm = "kubectl -n $ns get po/$pod -o jsonpath={.spec.containers[*].name}";
    map{ my $c = $_; sub{ my($stm)=@_; &$exec_stm_kc($ns,"",$pod,$c,$stm) } }
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
my $single = sub{ @_==1 ? $_[0] : die };

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
    .'$c{main}=~s{<var:(\w+):([^>]+)>}{exists($vars{$1})?$vars{$1}:$2}eg;'
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
    my($name,$tmp_path,$spec_arg,$options) = @_;
    my %all = map{%$_} @$options;
    my @unknown = &$map(\%all,sub{ my($k,$v)=@_;
        $k=~/^([A-Z]|host:|port:|ingress:|path:)/ ||
        $k=~/^(tty|image|name|noderole)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    #
    my @host_aliases = do{
        my $ip2aliases = &$merge_list({},&$map(\%all,sub{ my($k,$v)=@_; $k=~/^host:(.+)/ ? {$v=>["$1"]} : () }));
        &$map($ip2aliases, sub{ my($k,$v)=@_; +{ip=>$k, hostnames=>$v} });
    };
    #
    my @int_secrets = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        my @files = &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^C4/ && $v=~m{^/c4conf/([\w\.]+)$} ? "$1" : ()
        });
        @files ? { container => $nm, secret => "$name-$nm", path=>"/c4conf", files => \@files } : ();
    } @$options;
    my @ext_secrets = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^C4/ && "$v/"=~m{^(/c4conf-([\w\-]+))/} ? {container=>$nm,secret=>"$2",path=>"$1"} : ()
        });
    } @$options;
    my @all_secrets = (@int_secrets,@ext_secrets);
    my @secret_volumes = &$map(
        +{map{(&$mandatory_of(secret=>$_)=>1)} @all_secrets},
        sub{ my($secret,$v)=@_;
            +{ name => "$secret-secret", secret => { secretName => $secret } }
        }
    );
    my %secret_mounts = &$map(
        &$merge_list({},map{
            +{&$mandatory_of(container=>$_)=>{&$mandatory_of(path=>$_)=>{&$mandatory_of(secret=>$_)=>1}}}
        } @all_secrets),
        sub{ my($container,$v)=@_;
            my @mounts = &$map($v,sub{ my($path,$v)=@_;
                my $secret = &$single(keys %$v);
                +{ name => "$secret-secret", mountPath => $path }
            });
            ($container=>\@mounts)
        }
    );
    #
    my @node_role = grep{$_} map{$$_{noderole}} @$options;
    my %affinity = !@node_role ? () : (affinity=>{ nodeAffinity=>{
        preferredDuringSchedulingIgnoredDuringExecution=> [{
            weight=> 1,
            preference=> { matchExpressions=> [
                { key=> "noderole", operator=> "In", values=> [&$single(@node_role)] }
            ]},
        }]
    }});
    #
    my %host_path_to_name = &$map(\%all,sub{ my($k,$v)=@_;
        $k=~m{^path:} ? ($v=>"host-vol-".md5_hex($v)) : ()
    });
    my @host_volumes = &$map(\%host_path_to_name, sub{ my($k,$v)=@_;
        +{ name=>$v, hostPath=>{ path=>$k } }
    });
    my %host_mounts = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        my @mounts = &$map($opt,sub{ my($k,$v)=@_;
            my $path = $k=~m{^path:(.*)$} ? $1 : undef;
            $path ? { mountPath=>$path, name=> &$mandatory_of($v=>\%host_path_to_name) } : ();
        });
        ($nm => \@mounts);
    } @$options;
    print join("|", map{@$_} values %host_mounts)."host_path_to_name0\n";

    #
    my @containers = map{
        my $opt = $_;
        my $nm = &$mandatory_of(name=>$opt);
        my @env = &$map($opt,sub{ my($k,$v)=@_;
            $k=~/^([A-Z].+)/ ? {name=>$1,value=>"$v"} : ()
        });
        my $data_dir = $$opt{C4DATA_DIR};
        my @volume_mounts = (
            @{$secret_mounts{$nm}||[]},
            $data_dir ? { name => "db4", mountPath => $data_dir } : (),
            @{$host_mounts{$nm}||[]},
        );
        +{
            name => $nm, args=>[$nm], image => &$mandatory_of(image=>$opt),
            env=>[@env],
            volumeMounts=>\@volume_mounts,
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
            $ENV{C4READINESS_PATH} ? (
                readinessProbe => {
                    periodSeconds => 3,
                    exec => { command => ["cat",$ENV{C4READINESS_PATH}] },
                },
            ):(),

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
    my $spec = {
            %$spec_arg,
            selector => { matchLabels => { app => $name } },
            template => {
                metadata => {
                    labels => { app => $name },
                },
                spec => {
                    containers => \@containers,
                    volumes => [@secret_volumes, @db4_volumes, @host_volumes],
                    hostAliases => \@host_aliases,
                    imagePullSecrets => [{ name => "regcred" }],
                    securityContext => {
                        runAsUser => 1979,
                        runAsGroup => 1979,
                        fsGroup => 1979,
                        runAsNonRoot => "true",
                    },
                    $all{is_deployer} ? (serviceAccountName => "deployer") : (),
                    %affinity,
                },
            },
    };
    my $stateful_set_yml = &$to_yml_str($all{C4ELECTOR_SERVERS} ? do{
        @volume_claim_templates and die "C4DATA_DIR can not be with C4ELECTOR_SERVERS";
        +{
            apiVersion => "apps/v1",
            kind => "Deployment",
            metadata => { name => $name },
            spec => $spec,
        }
    } : do{
        +{
            apiVersion => "apps/v1",
            kind => "StatefulSet",
            metadata => { name => $name },
            spec => {
                %$spec,
                serviceName => $name,
                @volume_claim_templates,
            },
        }
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
                $all{is_node_port} ? (type => "NodePort") : (),
                $all{headless} ? (clusterIP=>"None") : (),
            },
        }) : ();
    };
    #
    my @ingress_yml = do{
        my $by_host = &$merge_list({},&$map(\%all,sub{ my($k,$v)=@_;
            $k=~m{^ingress:([^/]+)(.*)$} ? {$1=>[{path=>$2,port=>$v-0}]} : ()
        }));
        my @hosts = &$map($by_host,sub{ my($host,$v)=@_; $host });
        my $disable_tls = 0; #make option when required
        my $ingress_secret_name = $all{ingress_secret_name};
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
        metadata => { name => &$mandatory_of(secret=>$_) },
        type => "Opaque",
        data => { map{($_=>syf("base64 -w0 < $tmp_path/$_"))} @{$$_{files}||die} },
    })} @int_secrets;
    #
    join("", @secrets_yml, @service_yml, @ingress_yml, $stateful_set_yml);
};

my $wrap_kc = sub{
    my($name,$tmp_path,$options) = @_;
    my $yml_str = &$make_kc_yml($name,$tmp_path,{ replicas => "<var:replicas:1>" },$options);
    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str)
        .&$interpolation_body($name)
        .q[my $ns=`cat /var/run/secrets/kubernetes.io/serviceaccount/namespace`;]
        .q[$ENV{C4FORCE_RECREATE} and system "kubectl -n $ns delete pods/].$name.q[-0" and die $?;] #todo fix for deployment's variable pod name
        .q[pp(main=>"kubectl -n $ns apply -f-");];
    ($name,undef,$up_content);
};
push @tasks, ["wrap_deploy-kc_host", "", $wrap_kc];

#todo apply -n $ns

#networks => { default => { aliases => ["broker","zookeeper"] } },

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
    my $put = &$rel_put_text($from_path);
    &$put($_,&$mandatory_of($_=>\%auth)) for "simple.auth";
};

my @var_img = (image=>"<var:image:''>");
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

push @tasks, ["up-client", "", sub{
    my($run_comp,$args)=@_;
    my $conf = &$get_compose($run_comp);
    my $from_path = &$get_tmp_dir();
    &$make_secrets($run_comp,$from_path);
    my $options = [{
        @var_img, name => "main",
        tty => "true", JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport",
        @req_small,
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

my $get_consumer_options = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $prefix = $$conf{C4INBOX_TOPIC_PREFIX};
    my ($bootstrap_servers,$elector) = &$get_deployer_conf($comp,1,qw[bootstrap_servers elector]);
    (
            &$all_consumer_options(),
            C4INBOX_TOPIC_PREFIX => ($prefix||die "no C4INBOX_TOPIC_PREFIX"),
            C4STORE_PASS_PATH => "/c4conf-kafka-auth/kafka.store.auth",
            C4KEYSTORE_PATH => "/c4conf-kafka-certs/kafka.keystore.jks",
            C4TRUSTSTORE_PATH => "/c4conf-kafka-certs/kafka.truststore.jks",
            C4BOOTSTRAP_SERVERS => ($bootstrap_servers||die "no host bootstrap_servers"),
            C4HTTP_SERVER => "http://$comp:$inner_http_port",
            C4ELECTOR_SERVERS => join(",",map{"http://$elector-$_.$elector:$elector_port"}0,1,2),
            C4READINESS_PATH=>"/c4/c4is-ready",
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
    ($run_comp, $from_path, [{
        @var_img, name => "main", %consumer_options,
        @req_big,
        C4FRPC_INI => "/c4conf/frpc.ini",
        %$conf,
    }]);
};
my $up_gate = sub{
    my($run_comp)=@_;
    my %consumer_options = &$get_consumer_options($run_comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($run_comp,$from_path);
    &$need_logback($run_comp,$from_path);
    my $hostname = &$get_hostname($run_comp) || die "no le_hostname";
    my ($ingress_secret_name) = &$get_deployer_conf($run_comp,0,qw[ingress_secret_name]);
    my @containers = ({
        @var_img,
        %consumer_options,
        name => "main",
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
    ($run_comp, $from_path, \@containers);
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

#push @tasks, ["restart","$composes_txt",sub{
#    my($comp)=@_;
#    sy(&$ssh_add());
#    my ($dir) = &$get_deployer_conf($comp,1,qw[dir]);
#    sy(&$remote($comp,"cd $dir/$comp && C4FORCE_RECREATE=1 ./up"));
#}];
push @tasks, ["del_pods","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $ns = &$get_kc_ns($comp);
    my $pods = join " ", &$get_pods($comp,$ns);
    $pods and sy(&$remote($comp,"kubectl -n $ns delete pods $pods"));
}];

### snapshot op-s

my $snapshot_name = sub{
    my($snnm)=@_;
    my @fn = $snnm=~/^(\w{16})(-\w{8}-\w{4}-\w{4}-\w{4}-\w{12}[-\w]*)$/ ? ($1,$2) : return;
    my $zero = '0' x length $fn[0];
    ["$fn[0]$fn[1]","$zero$fn[1]"]
};
push @tasks, ["get_snapshot", "$composes_txt [|snapshot|last]", sub{
    my($gate_comp,$arg)=@_;
    sy(&$ssh_add());
    my $prefix = &$get_compose($gate_comp)->{C4INBOX_TOPIC_PREFIX}
        || die "no C4INBOX_TOPIC_PREFIX for $gate_comp";
    my ($client_comp) = &$get_deployer_conf($gate_comp,1,qw[s3client]);
    my $mk_exec = &$find_exec_handler($client_comp);
    my $stm = sub{
        my($op,$fn) = @_;
        &$remote($client_comp,&$mk_exec("","main","/tools/mc $op def/$prefix.snapshots/$fn"))
    };
    if(!$arg){
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
    my %consumer_options = &$get_consumer_options($comp);
    my $from_path = &$get_tmp_dir();
    &$need_deploy_cert($comp,$from_path);
    "$from_path/simple.auth"
};

push @tasks, ["put_snapshot", "$composes_txt <file_path>", sub{
    my($comp,$data_path)=@_;
    sy(&$ssh_add());
    my $host = &$get_hostname($comp) || die "need le_hostname or domain_zone";
    &$put_snapshot(&$need_auth_path($comp),$data_path,"https://$host");
}];
push @tasks, ["put_snapshot_address", "${composes_txt} <to_address> <file_path> ", sub{
    my($comp,$address,$data_path)=@_;
    sy(&$ssh_add());
    &$put_snapshot(&$need_auth_path($comp),$data_path,$address);
}];
push @tasks, ["put_snapshot_local", "<file_path>", sub{
    my($data_path)=@_;
    my $auth_file = $ENV{C4AUTH_KEY_FILE} || die;
    &$put_snapshot($auth_file,$data_path,"http://127.0.0.1:$inner_http_port");
}];

###

push @tasks, ["history","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my ($dir) = &$get_deployer_conf($comp,1,qw[dir]);
    my $history = syf(&$remote($comp,"cat $dir/$comp.args"));
    my %vars = $history=~/([\w\-\.\:\/]+)/g;
    print "$history\n".join("",&$map(\%vars,sub{"[$_[0]=$_[1]]\n"}));
}];
my $get_comp_from_pod = sub{
    $_[0]=~/^(.+)-\d$/ ? "$1" :
    $_[0]=~/^(.+)-\w+-\w+$/ ? "$1" :
    die "bad pod name"
};
push @tasks, ["bash","<pod> [container]",sub{ #<replica>
    my($pod,$service)=@_;
    sy(&$ssh_add());
    my $comp = &$get_comp_from_pod($pod);
    my $ns = &$get_kc_ns($comp);
    my $service_str = $service ? "-c $service" : "";
    my $stm = qq[kubectl -n $ns exec -it $pod $service_str -- bash];
    sy(&$ssh_ctl($comp,"-t",$stm));
}];
push @tasks, ["watch","$composes_txt",sub{ #<replica>
    my($comp)=@_;
    sy(&$ssh_add());
    my $ns = &$get_kc_ns($comp);
    sy(&$ssh_ctl($comp,"-t",qq[watch kubectl -n $ns get po -l app=$comp]));
}];
push @tasks, ["log","<pod> [tail] [add]",sub{ #<replica>
    my($pod,$tail,$add)=@_;
    sy(&$ssh_add());
    my $comp = &$get_comp_from_pod($pod);
    my $ns = &$get_kc_ns($comp);
    my $tail_or = ($tail+0) || 100;
    my $stm = qq[kubectl -n $ns log -f $pod --tail $tail_or $add];
    sy(&$ssh_ctl($comp,"-t",$stm));
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
push @tasks, ["test_cd","<host>:<port> $composes_txt <pods|registry|'history $composes_txt'>",sub{
    # ? |'build <img>'
    my($addr,$comp,$args) = @_;
    sy(&$ssh_add());
    &$nc_sec($comp,$addr,"$args\n");
}];

#my $get_head_img_tag = sub{
#    my($repo_dir,$parent)=@_;
#    #my $repo_name = $repo_dir=~/(\w+)$/ ? $1 : die;
#    print "[$repo_dir][$parent]\n";
#    my $commit = (!-e $repo_dir) ?
#        ($repo_dir=~/^(\w+)$/ ? $1 : die) :
#        (syf("git --git-dir=$repo_dir/.git rev-parse --short HEAD")=~/(\w+)/ ? $1 : die);
#    #my $commit = syf("git --git-dir=$repo_dir/.git log -n1")=~/\bcommit\s+(\w{10})/ ? $1 : die;
#    !$parent ? "base.$commit" :
#    $parent=~/^(\w+)$/ ? "base.$1.next.$commit" :
#    die $parent;
#};
#
#my $get_head_img = sub{
#    my($req_pre,$repo_dir,$parent) = @_;
#    $repo_dir ? "$req_pre.".&$get_head_img_tag($repo_dir,$parent) : $req_pre;
#};

push @tasks, ["ci_cleanup","$composes_txt",sub{
    my ($builder_comp) = @_;
    my $conf = &$get_compose($builder_comp);
    my @ssh = &$ssh_ctl($builder_comp);
    my @to_kill = map{/^c4\s+(\d+).+\bdocker\s+build\b/?"$1":()} syl(join" ",@ssh,"ps","-ef");
    @to_kill and sy(@ssh,"kill",@to_kill);
    sy(@ssh,"test -e $tmp_root && rm -r $tmp_root; true");
}];

my $ci_build_img = sub{
    my ($builder_comp,$arg) = @_;
    my $conf = &$get_compose($builder_comp);
    my $allow = &$mandatory_of(C4CI_ALLOW=>$conf);
    my($full_img,$reg,$shrep,$tag,$base,$proj,$mode,$checkout) =
        $arg=~/^(([\w\-\.\:\/]*?)(\w+)\:((([\w\-]+)[\w\.]*)\.(\w+)\.([\w\-]+)))$/ ?
        ($1,$2,$3,$4,$5,$6,$7,$8) : die "can not [$arg]";
    index(" $allow "," $reg$shrep:$proj ") < 0 and die "prefix not allowed";
    my $builder_reg = index(" $allow "," ${reg}builder:$proj ") < 0 ? "" : $reg;
    #implement checkout lock?
    my $builder = md5_hex($full_img)."-".time;
    my %repo_urls = &$mandatory_of(C4CI_SHORT_REPO_REMOTES=>$conf)=~/(\S+)/g;
    my $repo_url = $repo_urls{$shrep} || die "no repo url for $shrep";
    my $repo_dir = &$need_path("/tmp/c4ci/$shrep");
    -e $repo_dir or sy("mkdir -p /tmp/c4ci && git clone $repo_url $repo_dir");
    my $local_dir = &$get_tmp_dir();
    sy("cd $repo_dir && git fetch && git fetch --tags && ".
      "git --git-dir=$repo_dir/.git --work-tree=$local_dir checkout $checkout -- .");
    my $ctx_dir = syf("uuidgen")=~/(\S+)/ ? "$tmp_root/$1" : die;
    &$rsync_to($local_dir,$builder_comp,$ctx_dir);
    my @args = ("--build-arg","C4CI_BASE_TAG=$base");
    my @commands = (
        ["-t","docker","build","-t","builder:$tag","-f","$ctx_dir/build.$mode.dockerfile",@args,$ctx_dir],
        $builder_reg ? (
            ["docker","tag","builder:$tag","${builder_reg}builder:$tag"],
            ["docker","push","${builder_reg}builder:$tag"],
        ) : (),
        ["rm","-r",$ctx_dir],
        ["docker","create","--name",$builder,"builder:$tag"],
        ["docker","cp","$builder:/c4/res",$ctx_dir],
        ["docker","rm","-f",$builder],
        ["-t","docker","build","-t",$full_img,$ctx_dir],
        $reg ? ["docker","push",$full_img] : (),
    );
    sy(&$ssh_ctl($builder_comp,@$_)) for @commands;
    print "prod up \$C4CURRENT_STACK 'image $arg'\n";
};

my $is_builder_repo = sub{ $_[0]=~m{/([^/]+)$} && $1 eq 'builder' };
my $ci_build_proj_tag = sub{
    my ($proj_tag_arg,$rebuild_base) = @_;
    my $proj_tag = $proj_tag_arg || $ENV{C4CI_BASE_TAG_ENV} || die 'proj-tag not found';
    my $comp = $ENV{C4COMPOSITION} || die "no C4COMPOSITION";
    my $builder_comp = $ENV{C4CI_BUILDER} || die "no C4CI_BUILDER";
    print "builder: $builder_comp\n";
    #
    my $conf = &$get_compose($builder_comp);
    my @proj_repos = map{ m{(.+):([^:]+)$} && $2 eq $proj_tag ? "$1":() }
        ($$conf{C4CI_ALLOW}||die)=~/(\S+)/g;
    my $repo = (grep{ !&$is_builder_repo($_) } @proj_repos)[0] || die 'proj-tag not allowed';
    #
    my ($head,@log) = syf("cat $ENV{C4CI_BUILD_DIR}/target/c4git-log")=~/(\S+)/g;
    $head || die 'commit not found';
    my $ideal_tag = "$proj_tag.base.$head";
    my @next_tags = $rebuild_base ? () : do{
        my %has_img = map{/^(\S+)\s+(\S+)/?("$1:$2"=>1):()}
            syl(&$remote($builder_comp,"docker images"));
        my $builder_repo = (grep{ &$is_builder_repo($_) } @proj_repos)[0] || die 'proj-tag builder not allowed';
        $has_img{"$repo:$ideal_tag"} ? () :
            map{ $has_img{"$builder_repo:$_"} ? "$_.next.$head" : () }
            map{"$proj_tag.base.$_"} @log
    };
    my ($target_tag) = (@next_tags,$ideal_tag);
    #

    print join "",map{"## $_\n"} @next_tags,$ideal_tag;

    &$ci_build_img($builder_comp,"$repo:$target_tag");
    my @cont = map{
        &$is_builder_repo($_) ? "prod up $comp 'image $_:$target_tag'" : (
            "prod ssh $builder_comp docker tag $repo:$target_tag $_:$target_tag",
            "prod ssh $builder_comp docker push $_:$target_tag",
        )
    } grep{$_ ne $repo} @proj_repos;
    @cont and print join "", map{"$_\n"} "### what's next:", @cont;
};

push @tasks, ["ci_build_img","<builder> [img]",sub{
    my ($builder_comp,$img) = @_;
    sy(&$ssh_add());
    if($img){
        &$ci_build_img($builder_comp,$img);
    } else {
        my $conf = &$get_compose($builder_comp);
        print sort map{"$_\n"} ($$conf{C4CI_ALLOW}||die)=~/(\S+)/g;
    }
}];
push @tasks, ["ci_build_head","[proj-tag]",sub{
    my ($proj_tag) = @_;
    sy(&$ssh_add());
    &$ci_build_proj_tag($proj_tag,0);
}];
push @tasks, ["ci_rebuild_head","[proj-tag]",sub{
    my ($proj_tag) = @_;
    sy(&$ssh_add());
    &$ci_build_proj_tag($proj_tag,1);
}];

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
#push @tasks, ["ci_setup","<builder>",sub{
#    my($comp) = @_;
#    my $from_path = &$get_tmp_dir();
#    my $put = &$rel_put_text($from_path);
#    my $ver = "v2";
#    &$put("Dockerfile", join "\n",
#        "FROM ghcr.io/conecenter/c4replink:$ver",
#        "COPY --chown=c4:c4 .tmp-ssh/* /c4/.ssh/",
#    );
#    sy(&$ssh_add());
#    my $temp = syf("hostname")=~/(\w+)/ ? "c4build_temp/$1/replink" : die;
#    my $tag = "builder:replink-with-keys-$ver";
#    &$rsync_to($from_path,$comp,$temp);
#    my $uid = syf(&$remote($comp,"id -u"))=~/(\d+)/ ? $1 : die;
#    sy(&$remote($comp, join " && ",
#        "mkdir -p $temp/.tmp-ssh",
#        "cp \$HOME/.ssh/known_hosts \$HOME/.ssh/id_rsa $temp/.tmp-ssh",
#        "docker build -t $tag --build-arg C4UID=$uid $temp",
#    ));
#}];

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
    my $mod  = syf("cat $gen_dir/.bloop/c4/tag.$base.mod" )=~/(\S+)/ ? $1 : die;
    my $main = syf("cat $gen_dir/.bloop/c4/tag.$base.main")=~/(\S+)/ ? $1 : die;
    my $paths = JSON::XS->new->decode(syf("cat $gen_dir/.bloop/c4/mod.$mod.classpath.json"));
    my @classpath = $$paths{CLASSPATH}=~/([^\s:]+)/g;
    my @started = map{&$start($_)} map{
        m{([^/]+\.jar)$} ? "cp $_ $ctx_dir/app/$1" :
        m{([^/]+)\.classes(-bloop-cli)?$} ? "cd $_ && zip -q -r $ctx_dir/app/$1.jar ." : die $_
    } @classpath;
    &$_() for @started;
    &$put_text("$ctx_dir/serve.sh","export C4APP_CLASS=$main\nexec java ee.cone.c4actor.ServerMain");
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
    print "[purging]\n";
    for(sort{$b cmp $a} syl("cat $gen_dir/ci.rm")){ chomp $_ or die "[$_]"; unlink $_; rmdir $_ }
}];

my $put_reg_ssh_client_conf = sub{
    my($from_path,$host,$port)=@_;
    my $key_dir = &$get_tmp_dir();
    sy(join ' && ',
        "cd $key_dir",
        "ssh-keygen -f id_rsa",
        "ssh-keyscan -H $host > known_hosts",
        "tar -czf $from_path/ssh.tar.gz .",
        "ssh-copy-id -i id_rsa.pub -p $port c4\@$host"
    );
};

push @tasks, ["up-dc_host","",sub{
    my ($comp,$args) = @_;
    my ($host,$port,$user) = &$get_host_port($comp);
    my $conf_cert_path = &$get_conf_cert_path().".pub";
    sy("ssh-copy-id -i $conf_cert_path $user\@$host -p $port");
    my $groups = syf(&$remote($comp,"groups"));
    " $groups "=~/\sdocker\s/ or sy(&$ssh_ctl($comp,"-t","sudo usermod -aG docker $user"));
}];

my $make_frp_image = sub{
    my ($comp) = @_;
    my $gen_dir = &$get_proto_dir();
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
    my ($comp,$args) = @_;
    my $img = &$make_frp_image($comp);
    my @containers = ({
        image => $img,
        name => "frpc",
        C4FRPC_INI => "/c4conf/frpc.ini",
        @req_small,
    });
    my $from_path = &$get_tmp_dir();
    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
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
    map{ my($name,$port,$host) = @$_; [$port=>"$comp.$name"] } @$services;
};

push @tasks, ["up-visitor", "", sub{
    my ($comp,$args) = @_;
    my $conf = &$get_compose($comp);
    my $server_comp = $$conf{peer};
    my $img = &$make_frp_image($comp);
    my @services = $server_comp ? &$get_visitor_conf($server_comp) : ();
    my @ports = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^port:/ ? ($k=>$v) : ()
    });
#    map{
#        my($port,$name) = @$_;
#        my $ext_port = $port == $ssh_port ? 22 : $port;
#        ("port:$ext_port:$port" => "")
#    } @services;
    my @visits = &$map($conf,sub{ my($k,$v)=@_;
        $k=~/^visit:(\d+)$/ ? ["$1"=>$v] : ()
    });
    my @add_ports = map{ my($port,$nm)=@$_; ("port:$port:$port"=>"") } @visits;
    my @containers = ({
        image => $img,
        name => "frpc",
        C4FRPC_INI => "/c4conf/frpc.visitor.ini",
        @ports, @add_ports,
        @req_small,
    });
    my $from_path = &$get_tmp_dir();
    &$make_visitor_conf($comp,$from_path,[@services,@visits]);
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

push @tasks, ["up-s3client", "", sub{
    my ($comp,$args) = @_;
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
    my @containers = ({
        image => $img,
        name => "main",
        C4S3_CONF_DIR => "/c4conf-ceph-client",
        @req_small,
    });
    my $from_path = &$get_tmp_dir();
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

my $install_kubectl = sub{
    "RUN curl -LO https://storage.googleapis.com/kubernetes-release/release/v1.14.0/bin/linux/amd64/kubectl "
    ."&& chmod +x ./kubectl "
    ."&& mv ./kubectl /usr/bin/kubectl "
};
my $install_tini = sub{
    "RUN perl install.pl curl https://github.com/krallin/tini/releases/download/v0.19.0/tini"
    ."&& chmod +x /tools/tini"
};

push @tasks, ["up-kc_host", "", sub{
    my ($comp,$args) = @_;
    my $conf = &$get_compose($comp);
    my $gen_dir = &$get_proto_dir();
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
            &$install_kubectl(),
            "RUN mkdir /c4db && chown c4:c4 /c4db",
            "COPY id_rsa.pub cd.pl /",
            "USER c4",
            "RUN mkdir /c4/.ssh /c4/dropbear".
            " && cat /id_rsa.pub > /c4/.ssh/authorized_keys".
            " && chmod 0600 /c4/.ssh/authorized_keys",
            "ENV C4SSH_PORT=$ssh_port",
            'ENTRYPOINT ["perl", "/cd.pl"]',
        );
        &$remote_build(''=>$comp,$from_path);
    };
    my @containers = (
        {
            image => $img,
            name => "sshd",
            C4DATA_DIR => "/c4db",
            #$external_ssh_port ? ("port:$external_ssh_port:$ssh_port" => "node") : (),
            @req_small,
        },
        {
            image => $img,
            name => "kubectl",
            is_deployer => 1,
            is_node_port => 1,
            @req_small,
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
            @req_small,
        },
        {
            image => $img,
            name => "frpc",
            C4FRPC_INI => "/c4conf/frpc.ini",
            @req_small,
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
    print "########\n$add_yml$yml_str";
}];

push @tasks, ["visit-kc_host", "", sub{
    my($comp)=@_;
    [[main=>$cicd_port],[ssh=>$ssh_port]]
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
    my($comp,$args)=@_;
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
        @req_small,
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
push @tasks, ["thread_print","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    my $mk_exec = &$find_exec_handler($comp);
    my($get_pids,$prn) = &$tp_run(sub{ my($cmd)=@_; &$remote($comp,&$mk_exec("",$service,$cmd)) });#/RUNNABLE/
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
    die "$@ -- $expr" if $@;
    print grep{&$by} &$tp_split(join '',<STDIN>);
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
push @tasks, ["greys_local","<pid>",sub{
    my($pid)=@_;
    $pid || die;
    -e "$ENV{HOME}/.greys" or sy("cd /tools/greys && bash ./install-local.sh");
    sy("/tools/greys/greys.sh $pid");
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

push @tasks, ["up-elector","",sub{
    my ($comp,$args) = @_;
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
    my @containers = ({
        image => $img, name => "main", tty => "true", headless=>1,
        C4HTTP_PORT => $elector_port, "port:$elector_port:$elector_port"=>"",
        @req_small
    }); #todo: affin, replicas 3
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

push @tasks, ["up-dconf","",sub{
    my ($comp,$args) = @_;
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
    #
    my $cmd = do{
        my $conf = &$get_compose($comp);
        my($host,$port,$path) = &$parse_deploy_location($$conf{C4DEPLOY_LOCATION});
        &$put_reg_ssh_client_conf($from_path,$host,$port);
        my $dir = "/c4/.ssh";
        "mkdir -p $dir && cd $dir && chmod 0700 . && tar -xzf \$C4CI_KEY_TGZ && ssh -p $port c4\@$host 'cd $path && git pull'"
    };
    #
    my @containers = ({
        image => $img,
        name => "main",
        tty => "true",
        "port:$inner_http_port:$inner_http_port"=>"",
        "ingress:$hostname/"=>$inner_http_port,
        C4HTTP_PORT => $inner_http_port,
        C4CI_KEY_TGZ => "/c4conf/ssh.tar.gz",
        C4CI_CMD => $cmd,
        @req_small
    });
    &$sync_up(&$wrap_deploy($comp,$from_path,\@containers),$args);
}];

my $ckh_secret =sub{ $_[0]=~/^([\w\-\.]{3,})$/ ? "$1" : die 'bad secret name' };
push @tasks, ["get_secret","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my $secret_name = &$ckh_secret($secret_name_arg);
    sy(&$ssh_add());
    rename $secret_name, "$secret_name-".time or die if -e $secret_name;
    my $ns = &$get_kc_ns($comp);
    my $json = JSON::XS->new->pretty;
    my $stm = "kubectl -n $ns get secret/$secret_name -o json";
    my $data = $json->decode(syf(&$remote($comp,$stm)))->{data} || die;
    for(sort keys %$data){
        my $v64 = $$data{$_};
        my $fn = &$need_path("$secret_name/".&$ckh_secret($_));
        sy("base64 -d > $fn < ".&$put_temp("value",$v64));
    }
}];

push @tasks, ["set_secret","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my $secret_name = &$ckh_secret($secret_name_arg);
    sy(&$ssh_add());
    my $ns = &$get_kc_ns($comp);
    my $json = JSON::XS->new;
    -e $secret_name or die;
    my $data = {map{
        my $k = substr $_, 1+length $secret_name;
        my $v = syf("base64 -w0 < $_");
        ($k,$v)
    } sort <$secret_name/*>};
    my $secret = $json->encode({
        apiVersion => "v1", kind => "Secret", type => "Opaque", data => $data,
        metadata => { name => $secret_name },
    });
    syf(&$remote($comp,"kubectl -n $ns apply -f-")." < ".&$put_temp("secret",$secret));
}];

####

&$main(@ARGV);
&$cleanup();
