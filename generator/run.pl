
use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;
use Time::HiRes;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $cat = sub{
    my $args = join " ", grep{-e} @_;
    $args ? syf("cat $args") : ""
};
my $find_files = sub{
    my $from = join(" ", @_)||die;
    my @res = syf("find $from -type f")=~/(.*)/g;
    sort @res;
};
my $need_path = sub{
    my($path)=@_;
    my $dir = $path=~m"^(.+)/[^/]*$" ? $1 : die;
    -d $dir or sy("mkdir -p $dir");
    $path;
};
my $single = sub{ @_==1 ? $_[0] : die };
my $group = sub{ my %r; push @{$r{$$_[0]}||=[]}, $$_[1] for @_; (sub{@{$r{$_[0]}||[]}},[sort keys %r]) };
my $distinct = sub{ my(@r,%was); $was{$_}++ or push @r,$_ for @_; @r };
my $lazy_dict = sub{
    my($f)=@_;
    my %h; my $get;
    return $get = sub{ my($k)=@_; @{$h{$k} ||= [&$f($k,$get)]} };
};
my $if_changed = sub{
    my($path,$will,$then)=@_;
    return if &$cat($path) eq $will;
    print "rebuild started for $path\n";
    my $from = Time::HiRes::time;
    &$then();
    print "rebuild finished for $path in ",Time::HiRes::time-$from," s\n";
    &$put_text($path,$will);
};
my $get_sum = do{
    my $ver = "013";
    sub{ md5_hex("${ver}v\n$_[0]") };
};
my $json = sub{ JSON::XS->new->ascii(1)->canonical(1)->pretty(1) };
#
my $bloop_conf_to_classpath = sub{
    my($conf)=@_;
    my $project = $$conf{project}||die;
    (@{$$project{classpath}||die},($$project{classesDir}||die))
};
my $parse_dependencies = sub{
    my ($dep_content) = @_;
    my @lines = $dep_content=~/(.+)/g;
    my @dependencies = map{ m"^//(C4\w+)\s+(\S+)\s+(\S+)\s*$" ? +{tp=>$1,from=>$2,to=>$3}:() } @lines;
    my ($dep_conf) = &$group(map{[$$_{tp},$_]} @dependencies);
    $dep_conf;
};
my $calc_bloop_conf = sub{
    my($dir,$tmp,$dep_conf,$coursier_out) = @_;
    my @mod_names = &$distinct(
        (map{($$_{from},$$_{to})} &$dep_conf("C4DEP")),
        (map{$$_{from}} &$dep_conf("C4EXT")),
    );
    my ($ext_dep_by_from) = &$group(map{[$$_{from},$$_{to}]} &$dep_conf("C4EXT"));
    my ($int_dep_by_from) = &$group(map{[$$_{from},$$_{to}]} &$dep_conf("C4DEP"));
    my @resolved = @{$$coursier_out{dependencies}||die};
    my %resolved_by_name = map{($$_{coord}=>$_)} @resolved;
    my %scala_jars = map{m"/scala-(\w+)-[^/]*\.jar$"?("$1"=>$_):()} map{$$_{file}} @resolved;
    my $scala = {
        "organization" => "org.scala-lang",
        "name" => "scala-compiler",
        "version" => "2.13.0",
        "options" => [],
        "jars" => [grep{$_||die}@scala_jars{qw[library compiler reflect]}],
    };
    my %external_to_jars =
        map{ ($$_{coord}=>[map{$$_{file}} $_,@resolved_by_name{@{$$_{dependencies}||die}}]) }
        values %resolved_by_name;
    my %mod_group_path_by_name = map{($$_{from}=>$$_{to})} &$dep_conf("C4GROUP");
    my $conf_by_name = &$lazy_dict(sub{
        my($k,$get)=@_;
        my @local_dependencies = &$int_dep_by_from($k);
        my @classpath = &$distinct(
            ($scala_jars{library}||die),
            (map{@$_}@external_to_jars{&$ext_dep_by_from($k)}),
            (map{&$bloop_conf_to_classpath($_)} map{&$get($_)} @local_dependencies),
        );
        my ($mod_gr,@pkg_parts) = $k=~/(\w+)/g;
        my $pkg_path = join "/", @pkg_parts;
        my $mod_group_path = $mod_group_path_by_name{$mod_gr} || die "missing mod group: $mod_gr";
        my @sources = grep{-e}
            "$dir/$mod_group_path/scala/$pkg_path",
            "$dir/$mod_group_path/java/$pkg_path";
        #todo resources back to scala
        my $setup_scala_compiler = 1;
        my $project = {
            "name" => $k,
            "directory" => "$tmp/mod.$k",
            "sources" => \@sources,
            "dependencies" => \@local_dependencies,
            "classpath" => \@classpath,
            "out" => "$tmp/mod.$k.out",
            "classesDir" => "$tmp/mod.$k.classes",
            ($setup_scala_compiler ? ("scala"=>$scala) : ()),
        };
        (+{ "version" => "1.0.0", "project" => $project });
    });
    my @bloop_will = map{
        my $conf = &$single(&$conf_by_name($_));
        my $classpath = join ":", &$bloop_conf_to_classpath($conf);
        my $classpath_sh = "export CLASSPATH=$classpath";
        (
            +{ fn=>"$dir/.bloop/$_.json", content=>&$json()->encode($conf) },
            +{ fn=>"$tmp/mod.$_.classpath", content=>$classpath },
            +{ fn=>"$tmp/mod.$_.classpath.sh", content=>$classpath_sh },
        )
    } @mod_names;
    my $src_dirs_by_name = &$lazy_dict(sub{
        my($k,$get)=@_;
        my @own = map{@{$$_{project}{sources}||die}} &$conf_by_name($k);
        my @local_dependencies = &$int_dep_by_from($k);
        my @res = &$distinct(@own, map{@$_} map{&$get($_)} @local_dependencies);
        @res
    });
    (\@bloop_will,$src_dirs_by_name);
};
my $calc_sbt_conf = sub{
    my($src_dirs,$externals)=@_;
    join "\n",
        "libraryDependencies ++= ",
        (map{"  ($_) ::"} map{join " % ",map{qq^"$_"^}/([^:]+)/g} @$externals),
        "  Nil;",
        "unmanagedSourceDirectories in Compile ++= ",
        (map{qq^  (baseDirectory.value / "$_") ::^} sort @$src_dirs),
        "  Nil;";
};
#
my $src_dir = syf("pwd")=~/^(\S+)\s*$/ ? $1 : die;
my $tmp = "$src_dir/.bloop/c4";
my $dep_content = &$cat($ENV{C4GENERATOR_DEP} || die);
my $dep_conf = &$parse_dependencies($dep_content);
my @src_dirs = &$distinct(map{$$_{to}=~m"^(.+/src)/main$"?"$1":()} &$dep_conf("C4GROUP"));
my $gen_mod = &$single(&$dep_conf("C4GENERATOR_MAIN"))->{to}||die;
#
&$if_changed("$tmp/bloop-conf-in-sum",&$get_sum($dep_content), sub{
    my $externals = [&$distinct(map{$$_{to}} &$dep_conf("C4EXT"))];
    my $dependencies_args = join " ",sort @$externals;
    my $coursier_out_path = &$need_path("$tmp/coursier-out.json");
    &$if_changed("$tmp/coursier-in-sum",&$get_sum($dependencies_args), sub{
        sy("coursier fetch -j $coursier_out_path $dependencies_args");
    });
    my $coursier_out = &$json()->decode(&$cat($coursier_out_path));
    my ($bloop_will,$src_dirs_by_name) = &$calc_bloop_conf($src_dir,$tmp,$dep_conf,$coursier_out);
    &$put_text(&$need_path($$_{fn}),$$_{content}) for @$bloop_will;
    &$put_text(&$need_path("$tmp/generator-src-dirs"), join " ", &$src_dirs_by_name($gen_mod));
    &$put_text("$src_dir/c4gen-generator.sbt", &$calc_sbt_conf(\@src_dirs,$externals));
});
#
&$put_text(&$need_path("$tmp/gen/src"),join"\n", grep{!m"/c4gen-[^/]+$"} &$find_files(map{"$src_dir/$_"}@src_dirs));
#
my $sum = &$get_sum(&$cat(grep{/\.scala$/} &$find_files(&$cat("$tmp/generator-src-dirs")=~/(\S+)/g)));
&$if_changed("$tmp/generator-src-sum",$sum,sub{
    sy("cd $src_dir && bloop compile $gen_mod");
});
print "generation starting\n";
my $main = &$single(&$dep_conf("C4GENERATOR_MAIN"))->{from}||die;
sy(". $tmp/mod.$gen_mod.classpath.sh && C4GENERATOR_VER=$sum C4GENERATOR_PATH=$tmp/gen java $main");
print "generation finished\n";
