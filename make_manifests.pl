
use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };

my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

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

#todo: affinity for headless, replicas 3
#todo securityContext/runAsUser auto?

my $spaced_list = sub{ map{ ref($_) ? @$_ : /(\S+)/g } @_ };

my $make_kc_yml = sub{
    my($opt) = @_;
    my $name = &$mandatory_of(name=>$opt);
    my @unknown = &$map($opt,sub{ my($k)=@_;
        $k=~/^([A-Z]|host:|port:|ingress:|path:|label:)/ ||
        $k=~/^(tty|image|noderole|image_pull_secrets|ingress_secret_name|need_pod_ip|replicas|req_cpu|req_mem|ca)$/ ? () : $k
    });
    @unknown and warn "unknown conf keys: ".join(" ",@unknown);
    my $nm = "main";
    #
    my @host_aliases = do{
        my $ip2aliases = &$merge_list({},&$map($opt,sub{ my($k,$v)=@_; $k=~/^host:(.+)/ ? {$v=>["$1"]} : () }));
        &$map($ip2aliases, sub{ my($k,$v)=@_; +{ip=>$k, hostnames=>$v} });
    };
    #
    &$map($opt,sub{ my($k,$v)=@_; $k=~/^C4/ && $v=~m{^/c4conf/} and die "internal secrets are not supported" });
    my @all_secrets = &$map($opt,sub{ my($k,$v)=@_;
        $k=~/^C4/ && "$v/"=~m{^(/c4conf-([\w\-]+))/} ? {secret=>"$2",path=>"$1"} : ()
    });
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
                    memory => $$opt{lim_mem} || "64Gi",
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
                    labels => {
                        &$map($opt,sub{ my($k,$v)=@_; $k=~/^label:(c4\w+)$/ ? ("$1"=>$v) : () }),
                        app => $name,
                    },
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
    [@service_yml, @ingress_yml, $stateful_set_yml];
};

my $decode = sub{ JSON::XS->new->decode(@_) };
my $encode = sub{
    my($generated) = @_;
    my $yml_str = JSON::XS->new->canonical(1)->encode($generated);
    $yml_str=~s/("\w+":\s*)"(true|false)"/$1$2/g;
    $yml_str
};

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $main = sub{
    my %opt = @_;
    my @out = map{&$make_kc_yml($_)} @{&$decode(&$mandatory_of("--values",\%opt))};
    &$put_text(&$mandatory_of("--out",\%opt), join "\n", map{&$encode($_)} @out);
};

&$main(@ARGV);
