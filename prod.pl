#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

my $sys_image_ver = "v95";

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
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
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

my $single_or_undef = sub{ @_==1 ? $_[0] : undef };

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };

my $resolve = cached{
    my $kubectl = &$get_kubectl_raw(&$mandatory_of(C4DEPLOY_CONTEXT=>\%ENV));
    my $dir = &$get_tmp_dir();
    &$secret_to_dir($kubectl,"c4dconf-pl",$dir);
    # no need c4deploy_conf_repo
    my $conf_all = require "$dir/main.pl";
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
            $comp=~/^$k$/ ? {&$v(map{"$_"}@{^CAPTURE})} : die
        } $comp=~/^($re)$/ ? keys %+ : ())
    }
};

my $get_compose = sub{ &$resolve('')->($_[0]) || die "composition expected $_[0]" };

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

my $get_proto_dir = sub{ &$mandatory_of(C4CI_PROTO_DIR=>\%ENV) };
my $py_run = sub{
    my ($nm,@args) = @_;
    my $gen_dir = &$get_proto_dir();
    sy("python3","-u","$gen_dir/$nm",@args);
};

my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
};

####

push @tasks, ["","",sub{
    print "usage:\n", join '', sort map{"$_\n"}
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
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
    &$resolve('')->($arg) ? ($arg,&$get_pods($arg)) :
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
    my $kubectl = &$get_kubectl($comp);
    my $pods = join " ", &$get_pods($comp);
    $pods and sy("$kubectl delete pods $pods");
}];

#### composer

my $md5_hex = sub{ md5_hex(@_) };

my $spaced_list = sub{ map{ ref($_) ? @$_ : /(\S+)/g } @_ };

###

my @lim_small = (lim_mem=>"100Mi",lim_cpu=>"250m"); # use rarely, on lim_mem child processes inside container can be killed, and parent get mad
my @req_small = (req_mem=>"100Mi",req_cpu=>"250m");
my @req_big = (req_mem=>"10Gi",req_cpu=>"1000m");

my $inner_http_port = 8067;
my $inner_sse_port = 8068;
my $elector_port = 1080;

my $up_client = sub{
    my($run_comp)=@_;
    my $conf = &$get_compose($run_comp);
    +{
        tty => "true", JAVA_TOOL_OPTIONS => "-XX:-UseContainerSupport",
        @req_small, %$conf,
    };
};

my $get_consumer_options = sub{
    my($comp)=@_;
    my $conf = &$get_compose($comp);
    my $prefix = $$conf{C4INBOX_TOPIC_PREFIX};
    my ($bootstrap_servers,$elector) = &$get_deployer_conf($comp,1,qw[bootstrap_servers elector]);
    (
        tty                  => "true",
        JAVA_TOOL_OPTIONS    => "-XX:-UseContainerSupport ", # -XX:ActiveProcessorCount=36
        C4AUTH_KEY_FILE      => "/c4conf-simple-seed/value",
        C4INBOX_TOPIC_PREFIX => ($prefix || die "no C4INBOX_TOPIC_PREFIX"),
        C4STORE_PASS_PATH    => "/c4conf-kafka-auth/kafka.store.auth",
        C4KEYSTORE_PATH      => "/c4conf-kafka-certs/kafka.keystore.jks",
        C4TRUSTSTORE_PATH    => "/c4conf-kafka-certs/kafka.truststore.jks",
        C4BOOTSTRAP_SERVERS  => ($bootstrap_servers || die "no host bootstrap_servers"),
        C4S3_CONF_DIR        => "/c4conf-ceph-client",
        C4HTTP_SERVER        => "http://$comp:$inner_http_port",
        C4ELECTOR_SERVERS    => join(",", map {"http://$elector-$_.$elector:$elector_port"} 0, 1, 2),
        C4READINESS_PATH     => "/c4/c4is-ready",
        image_type           => "rt",
    )
};

my $up_consumer = sub{
    my($run_comp)=@_;
    my $conf = &$get_compose($run_comp);
    my $gate_comp = $$conf{ca} || die "no ca";
    my %consumer_options = &$get_consumer_options($gate_comp);
    my %fix_ceph = $$conf{C4CEPH_AUTH} eq "/c4conf/ceph.auth" ? (C4CEPH_AUTH=>"/tmp/ceph.auth") : ();
    +{ %consumer_options, @req_big, %$conf, %fix_ceph };
};
my $up_gate = sub{
    my($run_comp)=@_;
    my %consumer_options = &$get_consumer_options($run_comp);
    my $hostname = &$get_hostname($run_comp) || die "no le_hostname";
    my ($ingress_secret_name) = &$get_deployer_conf($run_comp,0,qw[ingress_secret_name]);
    my $conf = &$get_compose($run_comp);
    +{
        %consumer_options,
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
        (map{($_=>&$mandatory_of($_=>$conf))} qw[C4KEEP_SNAPSHOTS replicas project]),
        &$map($conf, sub{ my($k,$v)=@_; $k=~/^label:/ ? ($k,$v):() }),
    };
};
my $up_elector = sub{
    +{
        image_type => "elector", project => "def",
        tty => "true", headless => 1, replicas => 3,
        C4HTTP_PORT => $elector_port, "port:$elector_port:$elector_port"=>"",
        @req_small, @lim_small,
    };
};
my $conf_handler = {
    "consumer"=>$up_consumer, "gate"=>$up_gate, "client"=>$up_client,
    "s3client"=>sub{&$get_compose(@_)}, "elector"=>$up_elector, "resource_tracker"=>sub{&$get_compose(@_)},
};

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
    ("python3","-u","$gen_dir/req.py",$auth_path,$data_path,$addr,"/put-snapshot","/put-snapshot","snapshots/$data_fn");
};

push @tasks, ["snapshot_put", "<pod|$composes_txt> <file_path|nil> [to_address]", sub{
    my($arg, $data_path_arg, $address_arg)=@_;
    my $data_path = $data_path_arg ne "nil" ? $data_path_arg :
        &$put_temp("0000000000000000-d41d8cd9-8f00-3204-a980-0998ecf8427e","");
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $host = &$get_hostname($comp);
        my $address = $address_arg || $host && "https://$host" ||
            die "need le_hostname or domain_zone for $comp or address";
        my $kubectl = &$get_kubectl($comp);
        my $auth_path = &$get_tmp_dir()."/auth";
        sy(qq[$kubectl exec $pod -- sh -c 'cat \$C4AUTH_KEY_FILE' > $auth_path]);
        sy(&$snapshot_put($auth_path,$data_path,$address));
    });
}];

###

push @tasks, ["exec_bash","<pod|$composes_txt>",sub{
    my($arg)=@_;
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        sy(qq[$kubectl exec -it $pod -- bash]);
    });
}];
push @tasks, ["watch","$composes_txt",sub{
    my($comp)=@_;
    my $kubectl = &$get_kubectl($comp);
    sy(qq[watch $kubectl get po -l app=$comp]);
}];
push @tasks, ["log","[pod|$composes_txt] [tail] [add]",sub{
    my($arg_opt,$tail,$add)=@_;
    my $arg = $arg_opt || &$mandatory_of(C4INBOX_TOPIC_PREFIX=>\%ENV)."-main";
    &$for_comp_pod($arg,sub{ my ($comp,$pod) = @_;
        my $kubectl = &$get_kubectl($comp);
        my $tail_or = ($tail+0) || 100;
        sy(qq[$kubectl logs -f $pod --tail $tail_or $add]);
    });
}];
push @tasks, ["log_debug","<pod|$composes_txt> [class]",sub{ # ee.cone
    my($arg,$cl)=@_;
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

push @tasks, ["ci_deploy_info", "", sub{
    my(%opt)=@_;
    &$put_text(&$mandatory_of("--out",\%opt), &$encode([map{
        my $comp = $_;
        my ($context, $image_pull_secrets) = &$get_deployer_conf($comp,1,qw[context image_pull_secrets]);
        my ($allow_src, $to_repo_prop) = &$get_deployer_conf($comp,0,qw[allow_source_repo sys_image_repo]);
        my $to_repo = $allow_src ? "" : $to_repo_prop;
        my $options = &{$$conf_handler{&$get_compose($comp)->{type}} || die "no handler"}($comp);
        +{ context=>$context, to_repo=>$to_repo, image_pull_secrets=>$image_pull_secrets, %$options, name=>$comp }
    } map{ &$spaced_list(&$get_compose($_)->{parts}||[$_]) } &$mandatory_of("--env-state",\%opt)]));
}];

my $get_tag_info = sub{
    my($gen_dir,$tag)=@_;
    JSON::XS->new->decode(&$get_text("$gen_dir/target/c4/build.json"))->{tag_info}{$tag} || die;
};

my $if_changed = sub{
    my($path,$will,$then)=@_;
    return if (-e $path) && &$get_text($path) eq $will;
    my $res = &$then();
    &$put_text($path,$will);
    $res;
};
my $build_client = sub{
    my($dir, $mode)=@_;
    my $opt = $mode eq "fast" ? "--env.fast=true --mode development" : $mode eq "dev" ? "--mode development" :
        "--mode production";
    my $build_dir = "$dir/out";
    unlink or die $! for <$build_dir/*>;
    my $conf_dir = &$single_or_undef(grep{-e} map{"$_/webpack"} <$dir/src/*>) || die;
    &$if_changed("$dir/package.json", &$get_text("$conf_dir/package.json"), sub{1})
        and sy("cd $dir && npm install --no-save"); # --legacy-peer-deps
    sy("cd $dir && cp $conf_dir/webpack.config.js . && cp $conf_dir/tsconfig.json . && node_modules/webpack/bin/webpack.js --color $opt");# -d
    &$put_text("$build_dir/publish_time",time);
    &$put_text("$build_dir/c4gen.ht.links",join"",
        map{ my $u = m"^/(.+)$"?$1:die; "base_lib.ee.cone.c4gate /$u $u\n" }
        map{ substr $_, length $build_dir }
        sort <$build_dir/*>
    );
};
my $build_client_changed = sub{
    my($dir,$mode)=@_;
    $dir || die;
    my $j_dir = "$dir/target/c4/client/src";
    my $conf = &$decode(&$get_text("$dir/c4dep.main.json"));
    my %will = map{ref && $$_[0] eq "C4CLIENT" ? ("$j_dir/$$_[1]","$dir/$$_[2]/src"):()} @$conf;
    readlink($_) eq $will{$_} or unlink($_) or die $_ for <$j_dir/*>;
    -e $_ or symlink($will{$_}, &$need_path($_)) or die $! for sort keys %will;
    my @files = syf("cd $j_dir && find -L -type f")=~/(.+)/g;
    &$if_changed("$dir/target/c4/client-sums-compiled", syf("cd $j_dir && md5sum ".join " ", sort @files), sub{
        &$build_client("$dir/target/c4/client", $mode);
    });
};
push @tasks, ["build_client","",sub{ &$build_client_changed(@_) }]; # abs dir

my $chk_pkg_dep = sub{
    my($gen_dir,$mod)=@_;
    my $cp = &$get_text("$gen_dir/target/c4/mod.$mod.d/target/c4classpath");
    &$py_run("chk_pkg_dep.py", "by_classpath", $gen_dir, $cp);
};
push @tasks, ["chk_pkg_dep"," ",sub{
    my $gen_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $proto_dir = &$get_proto_dir();
    my $base = &$get_text("/c4/debug-tag");
    my $tag_info = &$get_tag_info($gen_dir,$base);
    my $mod = $$tag_info{mod} || die;
    &$chk_pkg_dep($gen_dir,$mod);
}];
my $install_jdk = sub{(
    "RUN perl install.pl curl https://download.bell-sw.com/java/17.0.8+7/bellsoft-jdk17.0.8+7-linux-amd64.tar.gz",
    #"RUN perl install.pl curl https://download.bell-sw.com/java/17.0.2+9/bellsoft-jdk17.0.2+9-linux-amd64.tar.gz",
)};

push @tasks, ["ci_rt_chk","",sub{ &$chk_pkg_dep(@_) }];
push @tasks, ["ci_rt_base","",sub{
    my %opt = @_;
    my $base = &$mandatory_of("--proj-tag", \%opt);
    my $gen_dir = &$mandatory_of("--context", \%opt);
    my $ctx_dir = &$mandatory_of("--out-context", \%opt);
    my $tag_info = &$get_tag_info($gen_dir,$base);
    my $add_steps = &$mandatory_of(steps => $tag_info);
    my $proto_dir = &$get_proto_dir();
    my @from_steps = grep{/^FROM\s/} @$add_steps;
    &$put_text("$ctx_dir/Dockerfile", join "\n",
        @from_steps ? @from_steps : "FROM ubuntu:22.04",
        "COPY --from=ghcr.io/conecenter/c4replink:v3kc /install.pl /",
        "RUN perl install.pl useradd 1979",
        "RUN perl install.pl apt".
        " curl software-properties-common".
        " lsof mc iputils-ping netcat-openbsd fontconfig".
        " openssh-client". #repl
        " python3", #vault
        &$install_jdk(),
        'ENV PATH=${PATH}:/tools/jdk/bin',
        (grep{/^RUN\s/} @$add_steps),
        "ENV JAVA_HOME=/tools/jdk",
        "RUN chown -R c4:c4 /c4",
        "WORKDIR /c4",
        "USER c4",
        'ENTRYPOINT ["perl","run.pl"]',
    );
}];

push @tasks, ["ci_rt_over","",sub{
    my %opt = @_;
    my $base = &$mandatory_of("--proj-tag", \%opt);
    my $gen_dir = &$mandatory_of("--context", \%opt);
    my $ctx_dir = &$mandatory_of("--out-context", \%opt)."/c4";
    my $proto_dir = &$get_proto_dir();
    my $tag_info = &$get_tag_info($gen_dir,$base);
    my ($mod,$main_cl) = map{$$tag_info{$_}||die} qw[mod main];
    sy("mkdir $ctx_dir");
    sy("cp $proto_dir/run.pl $proto_dir/vault.py $proto_dir/ceph.pl $ctx_dir/");
    mkdir "$ctx_dir/app";
    my $paths = &$decode(syf("python3 $proto_dir/build_env.py $gen_dir $mod"));
    my @started = map{&$start($_)} map{
        m{([^/]+\.jar)$} ? "cp $_ $ctx_dir/app/$1" :
        m{\bclasses\b} ? "cd $_ && zip -q -r $ctx_dir/app/".&$md5_hex($_).".jar ." :
        die $_
    } $$paths{CLASSPATH}=~/([^\s:]+)/g;
    &$_() for @started;
    &$put_text("$ctx_dir/serve.sh", join "\n",
        "export C4MODULES=$$paths{C4MODULES}",
        "export C4APP_CLASS=ee.cone.c4actor.ParentElectorClientApp",
        "export C4APP_CLASS_INNER=$main_cl",
        "exec java ee.cone.c4actor.ServerMain"
    );
    #
    my %has_mod = map{($_=>1)} $$paths{C4MODULES}=~/([^\s:]+)/g;
    my @public_part = map{ my $dir = $_;
        my @pub = map{ !/^(\S+)\s+\S+\s+(\S+)$/ ? die : $has_mod{$1} ? [$_,"$2"] : () }
            &$get_text("$dir/c4gen.ht.links")=~/(.+)/g;
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

push @tasks, ["up_kc_host", "", sub{ # the last multi container kc
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
                apiGroups => ["","apps","extensions","metrics.k8s.io"],
                resources => ["statefulsets","secrets","services","deployments","ingresses","pods","replicasets"],
                verbs => ["get","create","patch","delete","update","list","watch"],
            },
            {
                apiGroups => [""],
                resources => ["pods/exec","pods/portforward"],
                verbs => ["create"],
            },
            {
                apiGroups => [""],
                resources => ["pods/log"],
                verbs => ["get"],
            },
            {
                apiGroups => [""],
                resources => ["nodes"],
                verbs => ["list"],
            }
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

####

my $tp_split = sub{ "$_[0]\n\n"=~/(.*?\n\n)/gs };
my $sleep = sub{ select undef, undef, undef, $_[0] };
push @tasks, ["thread_print","$composes_txt",sub{
    my($comp)=@_;
    my @cmd = sort{$b<=>$a} map{
        my $pod = $_;
        map{&$kj_exec($comp,$pod,"","jcmd $_ Thread.print")}
        map{/^(\d+)\s+(ee\.cone\.\S+)/ ?"$1":()}
        syl(&$kj_exec($comp,$pod,"","jcmd"));
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
    #die "$@ -- $expr" if defined $@;
    print grep{&$by} &$tp_split(join '',<STDIN>);
}];
push @tasks, ["thread_count"," ",sub{
    my @r = grep{/\S/} &$tp_split(join '',<STDIN>);
    print scalar(@r)."\n";
}];

push @tasks, ["exec_repl","<pod|$composes_txt>",sub{
    my($arg)=@_;
    &$for_comp_pod($arg, sub{ my ($comp, $pod) = @_;
        sy(&$kj_exec($comp,$pod,"-it","test -e /c4/.ssh/id_rsa || ssh-keygen;ssh localhost -p22222"));
    });
}];

# todo restore greys
#"RUN ln -s /c4/greys /tools/greys",
#"USER c4",
#"COPY --chown=c4:c4 . /c4",
#"RUN cd /tools/greys && bash ./install-local.sh",
#sy("cd $ctx_dir && tar -xzf $proto_dir/tools/greys.tar.gz");
#push @tasks, ["greys_local","<pid>",sub{
#    my($pid)=@_;
#    $pid || die;
#    if(!-e "$ENV{HOME}/.greys"){
#        my $gen_dir = &$get_proto_dir();
#        sy("cd $ENV{HOME} && tar -xzf $gen_dir/tools/greys.tar.gz");
#        sy("cd $ENV{HOME}/greys && bash ./install-local.sh");
#    }
#    sy("$ENV{HOME}/greys/greys.sh $pid");
#}];
#push @tasks, ["greys","<pod|$composes_txt>",sub{
#    my($arg)=@_;
#    &$for_comp_pod($arg, sub{ my ($comp, $pod) = @_;
#        sy(&$kj_exec($comp,$pod,"-it","/tools/greys/greys.sh 1"));
#    });
#}];

push @tasks, ["exec_install","<pod|$composes_txt> <tgz>",sub{
    my($arg,$tgz)=@_;
    my ($comp,@pods) = &$get_comp_pods($arg);
    sy(&$kj_exec($comp,$_,"-i","tar -xz")." < $tgz") for @pods;
}];

my $dir_to_secret = sub{
    my($kubectl,$secret_name,$dir)=@_;
    -e $dir or die;
    my %data = map{ (substr($_, 1+length $dir)=>$_) } sort <$dir/*>;
    my $secret = &$encode(&$secret_yml_from_files($secret_name, \%data));
    syf("$kubectl apply -f ".&$put_temp("secret",$secret));
};

my $ckh_secret_dir =sub{ my $nm = &$ckh_secret($_[0]); ($nm,"c4conf-$nm") };
push @tasks, ["secret_get","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    rename $dir, "$dir-".time or die if -e $dir;
    my $kubectl = &$get_kubectl($comp);
    &$secret_to_dir($kubectl,$secret_name,$dir);
}];

push @tasks, ["secret_set","$composes_txt <secret-name>",sub{
    my($comp,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    my $kubectl = &$get_kubectl($comp);
    &$dir_to_secret($kubectl,$secret_name,$dir);
}];

push @tasks, ["secret_add_arg","$composes_txt <secret-content>",sub{
    my($comp,$secret_content)=@_;
    my $kubectl = &$get_kubectl($comp);
    my $hash = &$md5_hex($secret_content);
    my $secret_name = "c4hash-$hash";
    my $dir = &$get_tmp_dir();
    my $fn = "value";
    &$put_text("$dir/$fn",$secret_content);
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
    &$put_text("/c4/debug-tag",$tag||die);
    &$restart();
}];

push @tasks, ["restart"," ",sub{&$restart()}];

push @tasks, ["kafka","( topics | offsets <hours> | nodes | sizes <node> | topics_rm )",sub{
    my @args = @_;
    my $gen_dir = &$get_proto_dir();
    my $cp = syf("coursier fetch --classpath org.apache.kafka:kafka-clients:2.8.0")=~/(\S+)/ ? $1 : die;
    sy("JAVA_TOOL_OPTIONS= CLASSPATH=$cp java --source 15 $gen_dir/kafka_info.java ".join" ",@args);
}];

push @tasks, ["purge_mode_list","--list <list>",sub{ &$py_run("ci.py","purge_mode_list",@_) }];

push @tasks, ["purge_prefix_list","--list <list>",sub{ &$py_run("ci.py","purge_prefix_list",@_) }];

push @tasks, ["resources","( top <ctx> <search_str> | suggest <ctx> <level (ex 70)> )",sub{
    &$py_run("resources.py",@_)
}];

push @tasks, ["resources_set","$composes_txt <cpu=n|memory=nGi>",sub{
    my ($comp,$res) = @_;
    my ($context) = &$get_deployer_conf($comp,1,qw[context]);
    &$py_run("resources.py","set",$context,$comp,$res);
}];

####

&$main(@ARGV);
&$cleanup();
