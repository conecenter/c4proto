#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;
use FindBin;

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

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

my @tasks;

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
    sy("rm","-rf",$_) for grep{/(\d+)$/ and $$ eq $1 ||!-e "/proc/$1"} <$tmp_root/*>;
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
my $encode = sub{ JSON::XS->new->canonical(1)->encode(@_) };

my $get_kubectl_raw = sub{"kubectl --context ".($_[0]||die "need kube context")};

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

my $get_proto_dir = sub{ $FindBin::Bin || die };
my $py_run = sub{
    my ($nm,@args) = @_;
    my $gen_dir = &$get_proto_dir();
    sy("python3","-u","$gen_dir/$nm",@args);
};

my $main = sub{
    my($cmd,@args)=@_;
    ((grep{($cmd||'') eq $$_[0]} @tasks)[0]||die "unknown command ($cmd)")->[2]->(@args);
};

push @tasks, ["","",sub{
    print "usage:\n", join '', sort map{"$_\n"}
        (map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks);
}];

my $md5_hex = sub{ md5_hex(@_) };

push @tasks, ["log","<kube-context> [pod|deployment] [tail] [add]",sub{
    my($kube_context,$arg_opt,$tail,$add)=@_;
    my $arg_parts = scalar split '-', $arg_opt;
    my $arg = (
        $arg_parts == 0 ? &$mandatory_of(HOSTNAME=>\%ENV) :
        $arg_parts == 4 ? "deploy/$arg_opt-main" :
        $arg_parts == 5 ? "deploy/$arg_opt" : $arg_opt
    );
    my $kubectl = &$get_kubectl_raw($kube_context);
    my $tail_or = ($tail+0) || 100;
    sy(qq[$kubectl logs -f $arg --tail $tail_or $add]);
}];

my $get_tag_info = sub{
    my($gen_dir,$tag)=@_;
    JSON::XS->new->decode(&$get_text("$gen_dir/target/c4/build.json"))->{tag_info}{$tag} || die;
};

push @tasks, ["build_client","",sub{
    my $gen_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    sy("perl",&$get_proto_dir()."/build_client.pl",$gen_dir,@_);
}]; # abs dir

push @tasks, ["chk_pkg_dep"," ",sub{
    my $gen_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $base = &$get_text("/c4/debug-tag");
    my $tag_info = &$get_tag_info($gen_dir,$base);
    my $mod = $$tag_info{mod} || die;
    my $cp = &$get_text("$gen_dir/target/c4/mod.$mod.d/target/c4classpath");
    &$py_run("chk_pkg_dep.py", "by_classpath", $gen_dir, $cp);
}];

my $dir_to_secret = sub{
    my($kubectl,$secret_name,$dir)=@_;
    -e $dir or die;
    my %data = map{ (substr($_, 1+length $dir)=>$_) } sort <$dir/*>;
    my $secret = &$encode(&$secret_yml_from_files($secret_name, \%data));
    syf("$kubectl apply -f ".&$put_temp("secret",$secret));
};

my $ckh_secret_dir =sub{ my $nm = &$ckh_secret($_[0]); ($nm,"c4conf-$nm") };
push @tasks, ["secret_get","<kube-context> <secret-name>",sub{
    my($kube_context,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    rename $dir, "$dir-".time or die if -e $dir;
    my $kubectl = &$get_kubectl_raw($kube_context);
    &$secret_to_dir($kubectl,$secret_name,$dir);
}];

push @tasks, ["secret_set","<kube-context> <secret-name>",sub{
    my($kube_context,$secret_name_arg)=@_;
    my ($secret_name,$dir) = &$ckh_secret_dir($secret_name_arg);
    my $kubectl = &$get_kubectl_raw($kube_context);
    &$dir_to_secret($kubectl,$secret_name,$dir);
}];

push @tasks, ["secret_add_arg","<kube-context> <secret-content>",sub{
    my($kube_context,$secret_content)=@_;
    my $kubectl = &$get_kubectl_raw($kube_context);
    my $hash = &$md5_hex($secret_content);
    my $secret_name = "c4hash-$hash";
    my $dir = &$get_tmp_dir();
    my $fn = "value";
    &$put_text("$dir/$fn",$secret_content);
    &$dir_to_secret($kubectl,$secret_name,$dir);
    print qq[ADD TO CONFIG: "/c4conf-$secret_name/$fn"\n];
}];

my $supervisor = sub{ sy("supervisorctl","-c","/c4/supervisord.conf",@_) };
my $restart = sub{ &$supervisor("restart","build") };

push @tasks, ["debug_components","<on|off>",sub{
    my($arg)=@_;
    my $d_path = "/c4/debug-components";
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
push @tasks, ["stop"," ",sub{ &$supervisor("stop","app") }];
push @tasks, ["build"," ",sub{ &$py_run("build.py",&$mandatory_of(C4CI_BUILD_DIR => \%ENV)) }];

push @tasks, ["kafka","( topics | offsets <hours> | nodes | sizes <node> | topics_rm )",sub{
    my @args = @_;
    my $gen_dir = &$get_proto_dir();
    my $cp = syf("coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1")=~/(\S+)/ ? $1 : die;
    sy("JAVA_TOOL_OPTIONS= CLASSPATH=$cp java --source 15 $gen_dir/kafka_info.java ".join" ",@args);
}];

push @tasks, ["metrics_purge"," ",sub{
    my $now = time;
    my $url = &$mandatory_of(C4PROMETHEUS_POST_URL=>\%ENV)=~m{^(.+/metrics)\b} ? $1 : die;
    /^push_time_seconds\{instance="",job="([^\s"]+)"\} (\S+)\n/ && $now-$2 > 3600 and sy("curl -X DELETE $url/job/$1")
        for syl("curl $url");
}];

####

&$main(@ARGV);
&$cleanup();
