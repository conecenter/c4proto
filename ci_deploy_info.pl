
use strict;
use JSON::XS;

my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

my $single_or_undef = sub{ @_==1 ? $_[0] : undef };

my $map = sub{ my($opt,$f)=@_; map{&$f($_,$$opt{$_})} sort keys %$opt };

my $encode = sub{ JSON::XS->new->canonical(1)->encode(@_) };

my $load = sub{
    my ($conf_path) = @_;
    # no need c4deploy_conf_repo
    my $conf_all = require $conf_path;
    my @handlers = &$map($conf_all,sub{ my($k,$v)=@_;
        "CODE" eq ref $v ? [$k,$v] : ()
    });
    my $re = join "|",
        map{ my($k,$v) = @{$handlers[$_]||die}; "(?<p$_>$k)" }
            0..$#handlers;
    my %handlers = map{ ("p$_"=>$handlers[$_]||die) } 0..$#handlers;
    return sub{
        my ($comp) = @_;
        $$conf_all{$comp} || &$single_or_undef(map{
            my($k,$v) = @{$handlers{$_}||die};
            $comp=~/^$k$/ ? {&$v(map{"$_"}@{^CAPTURE})} : die
        } $comp=~/^($re)$/ ? keys %+ : ()) || die "composition expected $comp"
    }
};

my $fix_bools = sub{
    my($yml_str) = @_;
    $yml_str=~s/("\w+":\s*)"(true|false)"/$1$2/g;
    $yml_str
};

my $main = sub{
    my(%opt)=@_;
    my $get_compose = &$load(&$mandatory_of("--conf",\%opt));
    my $comp = &$mandatory_of("--env-state",\%opt);
    my $conf = &$get_compose($comp);
    my $d = &$get_compose($$conf{deployer}||die);
    my ($elector, $elector_port) = map{&$mandatory_of($_=>$d)} qw[elector elector_port];
    my $res = {
        (map{ ($_ => &$mandatory_of($_=>$d)) } qw[context image_pull_secrets]),
        (map{ $$d{$_} ? ($_ => $$d{$_}) : () } qw[ingress_secret_name ingress_api_version]), #gate
        C4ELECTOR_SERVERS => join(",", map{"http://$elector-$_.$elector:$elector_port"} 0, 1, 2), #consumer
        to_repo => $$d{allow_source_repo} ? "" : $$d{sys_image_repo},
        %$conf, &$map($conf, sub{ my($k,$v)=@_; $k=~/^v2:(.*)$/ ? ($1,$v) : () }),
        name => $comp,
    };
    print &$fix_bools(&$encode($res));
};

&$main(@ARGV);
