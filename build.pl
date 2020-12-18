
use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;
use Time::HiRes;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    print "put_text ($fn)\n";
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};
my $find_files = sub{
    my $from = join(" ", @_)||die;
    my @res = syf("find $from -type f")=~/(.+)/g;
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
    return if (-e $path) && &$get_text($path) eq $will;
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
my $from = sub{ map{ @$_==2 && $$_[0] || die } @_ };
my $to = sub{ map{ @$_==2 && $$_[1] || die } @_ };
#
my $bloop_conf_to_classpath = sub{
    my($conf)=@_;
    my $project = $$conf{project}||die;
    (@{$$project{classpath}||die},($$project{classesDir}||die))
    #my $out_dir = $$project{out} || die;
    #my $name = $$project{name} || die;
    #(@{$$project{classpath}||die},"$out_dir/bloop-bsp-clients-classes/mod.$name.classes-bloop-cli")
};
my $calc_bloop_conf = sub{
    my($dir,$tmp,$dep_conf,$coursier_out,$src_list) = @_;
    my %dir_exists = map{m"(.+)/[^/]+$"?("$1"=>1):die} $src_list=~/([^\n]+)/g;
    my @mod_names = &$distinct(
        (map{@$_} &$dep_conf("C4DEP")),
        &$from(&$dep_conf("C4EXT")),
        &$from(&$dep_conf("C4LIB")),
    );
    my ($ext_dep_by_from) = &$group(&$dep_conf("C4EXT"));
    my ($int_dep_by_from) = &$group(&$dep_conf("C4DEP"));
    my ($lib_dep_by_from) = &$group(&$dep_conf("C4LIB"));
    my @excl = &$distinct(&$to(&$dep_conf("C4EXCL")));
    my @resolved = @{$$coursier_out{dependencies}||die};
    my %resolved_by_name = map{($$_{coord}=>$_)} grep{$_||die} @resolved;
    my %scala_jars = map{m"/scala-(\w+)-[^/]*\.jar$"?("$1"=>$_):()} map{$$_{file}} @resolved;
    my $wartremover = &$single(grep{m{/wartremover/}} map{$$_{file}} @resolved);
    my $scala = {
        "organization" => "org.scala-lang",
        "name" => "scala-compiler",
        "version" => "2.13.1",
        "options" => [
            &$distinct(map{"-P:wartremover:traverser:$_"}&$to(&$dep_conf("C4WART"))),
            "-Xplugin:$wartremover",
        ],
        "jars" => [grep{$_||die}@scala_jars{qw[library compiler reflect]}],
    };
    my %external_to_jars = map{ my $d = $_;
        my @dep = grep{$_}
            map{ $resolved_by_name{$_} || do{ print "dep ignored: $_\n"; undef } }
            grep{ my $str = $_; !grep{0<index $str,$_} @excl }
            @{$$d{dependencies}||die};
        ($$d{coord}=>[grep{$_} map{$$_{file}} $d, @dep]);
    } values %resolved_by_name;
    my ($mod_group_path_by_name) = &$group(&$dep_conf("C4SRC"));
    my $conf_by_name = &$lazy_dict(sub{
        my($k,$get)=@_;
        my @local_dependencies = &$int_dep_by_from($k);
        my @classpath = &$distinct(
            ($scala_jars{library}||die),
            (map{@{$external_to_jars{$_}||die $_}}&$ext_dep_by_from($k)),
            (map{"$dir/$_"} &$lib_dep_by_from($k)),
            (map{&$bloop_conf_to_classpath($_)} map{&$get($_)} @local_dependencies),
        );
        my ($mod_gr,@pkg_parts) = $k=~/(\w+)/g;
        my $pkg_path = join "/", @pkg_parts;
        my @sources = grep{$dir_exists{$_}}
            map{"$dir/$_/$pkg_path"} &$mod_group_path_by_name($mod_gr);
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
    my @main_scala = map{ my($from,$to)=@$_; $from eq "main"?"$dir/$to":()} &$dep_conf("C4SRC");
    my @main_public = map{ my($from,$to)=@$_; $from eq "main"?"$dir/$to":()} &$dep_conf("C4PUB");
    @main_scala<=1 && @main_public<=1 or die;
    my @main_paths_will = map{+{ fn=>"$tmp/main_public_path", content=>$_ }} @main_public;
    my @bloop_will = map{
        my $conf = &$single(&$conf_by_name($_));
        my $classpath = join ":", &$bloop_conf_to_classpath($conf);
        my $sh = join "", map{"export $_\n"}
            (map{"C4GENERATOR_MAIN_SCALA_PATH=$_"} @main_scala),
            (map{"C4GENERATOR_MAIN_PUBLIC_PATH=$_"} @main_public),
            "CLASSPATH=$classpath";
        (
            +{ fn=>"$dir/.bloop/$_.json", content=>&$json()->encode($conf) },
            +{ fn=>"$tmp/mod.$_.classpath", content=>$classpath },
            +{ fn=>"$tmp/mod.$_.classpath.sh", content=>$sh },
        )
    } @mod_names;
    my ($step_by_project) = &$group(&$dep_conf("C4STEP"));
    my @tag2mod = map{
        my($from,$to)=@$_;
        my($mod,$cl) = $to=~/^(\w+\.)(.*)(\.\w+)$/ ? ("$1$2","$2$3") : die;
        my $steps = join "\n", &$step_by_project($from);
        (
            +{ fn=>"$tmp/tag.$from.to", content=>$to },
            +{ fn=>"$tmp/tag.$from.compile", content=>"exec perl $tmp/compile.pl $mod" },
            +{ fn=>"$tmp/tag.$from.mod", content=>$mod },
            +{ fn=>"$tmp/tag.$from.main", content=>$cl },
            +{ fn=>"$tmp/tag.$from.steps", content=>$steps },
        )
    } &$dep_conf("C4TAG");
    my $src_dirs_by_name = &$lazy_dict(sub{
        my($k,$get)=@_;
        my @own = map{@{$$_{project}{sources}||die}} &$conf_by_name($k);
        my @local_dependencies = &$int_dep_by_from($k);
        my @res = &$distinct(@own, map{@$_} map{&$get($_)} @local_dependencies);
        @res
    });
    ([@tag2mod,@bloop_will,@main_paths_will],$src_dirs_by_name);
};
my $calc_sbt_conf = sub{
    my($src_dirs,$externals)=@_;
    join "\n",
        'scalaVersion in ThisBuild := "2.13.1"','',
        "libraryDependencies ++= ",
        (map{"  ($_) ::"} map{join " % ",map{qq^"$_"^}/([^:]+)/g} @$externals),
        "  Nil",
        "",
        "unmanagedSourceDirectories in Compile ++= ",
        (map{qq^  (baseDirectory.value / "$_") ::^} sort @$src_dirs),
        "  Nil";
};
### AutoMixer
my $read = sub{map{+{fn=>$_,content=>&$get_text($_)}}@_};
my $get_gen_tasks = sub{
    my ($was_arg,$will_arg)=@_;
    my ($will_by_fn,$will_list) = &$group(map{[$$_{fn},$$_{content}]}@$will_arg);
    my ($was_by_fn,$was_list) = &$group(map{[$$_{fn},$$_{content}]}@$was_arg);
    map{
        my @was = &$was_by_fn($_);
        my @will = &$will_by_fn($_);
        @was==1 && @will==1 && $was[0] eq $will[0] ? () : [$_,@will]
    } &$distinct(@$was_list,@$will_list);
};
my $apply_gen_tasks = sub{
    for(@_){
        if(@$_==1){
            print "deleting $$_[0]\n";
            unlink $$_[0] or die $$_[0];
        } elsif(@$_==2) {
            print "putting $$_[0]\n";
            &$put_text(@$_);
        }
    }
};
my $apply_will = sub{
    my($by,$fns,$gr,$will)=@_;
    my @was = &$read(grep{&$by() eq $gr} @$fns);
    die @$_ for map{&$by() eq $gr ? ():[$gr,$_]} map{$$_{fn}} @$will;
    &$apply_gen_tasks(&$get_gen_tasks(\@was,$will));
};
my $to_parent = sub{ map{ m{^(.+)/[^/]+$} ? ("$1"):() } @_ };
my $gen_app_traits = sub{
    my($src_dir,$fns,$relations)=@_;
    #read
    my @index_scala = &$read(grep{m^/c4gen\.scala^}@$fns);
    #calc
    my ($dep) = &$group(@$relations);
    my ($def_app_by_mod_dir) = &$group(map{
        my @apps = $$_{content}=~/\bpackage\s+(\S+).*\ntrait\s+(\w+DefApp)\s/s ?"$1.$2":();
        map{ my $d = $_; map{ [$d,$_] } @apps } &$to_parent($$_{fn});
    } @index_scala);
    my $mod_data = &$lazy_dict(sub{
        my($k,$get)=@_;
        #print "mod: $_\n";
        my @deps = map{&$get($_)} &$dep($k);
        my ($main,@parts) = $k=~/(\w+)/g;
        my $infix = join "/", @parts;
        my $dir = "$src_dir/src/main/scala/$infix";
        my $def_app = join " with ", &$def_app_by_mod_dir($dir);
        my $has_deep_components = $def_app || @deps;
        return () if !$has_deep_components;
        $main eq 'main' || die;
        my $pkg = join ".", @parts;
        my $id = join "", map{ucfirst($_)} map{/([^_]+)/g} @parts;
        my $fn = "$dir/c4gen-base.scala";
        my $comp_content = $def_app ? "(new $def_app {}).components":"Nil";
        my $content = join "\n",
            "package $pkg",
            "object ${id}AutoMixer extends ee.cone.c4di.AutoMixer(",
            "  $comp_content,",
            (map{"  $$_{stm} ::"} @deps),
            "  Nil",
            ")";
        (+{stm=>"$pkg.${id}AutoMixer",fn=>$fn,content=>$content});
    });
    map{&$mod_data($_)} &$distinct(map{@$_}@$relations);
};

###
my $src_dir = syf("pwd")=~/^(\S+)\s*$/ ? $1 : die;
my $tmp = "$src_dir/.bloop/c4";
my $load_dep; $load_dep = sub{
    my $content = &$get_text("$src_dir/$_[0]");
    my $items = &$json()->decode($content);
    (
        ["C4RAW","",$content],
        map{
            my($tp,$from,$to)=@$_; $tp eq "C4INC" ? &$load_dep($to) : $_
        } map{
            @$_==3 || die @$_;
            my($tp,$from,$to)=@$_; ref($to) ? map{[$tp,$from,$_]}@$to : $_
        } grep{ref} @$items
    )
};

my @dep_list = &$load_dep("c4dep.main.json");
my ($dep_conf) = &$group(map{ my($tp,$from,$to)=@$_; [$tp,[$from,$to]] } @dep_list);
my $dep_content = join "\n", map{@$_} &$dep_conf("C4RAW");
my @src_dirs = &$distinct(&$to(&$dep_conf("C4SRC")));
my @pub_dirs = &$distinct(&$to(&$dep_conf("C4PUB")));
my $gen_mod = &$single(&$to(&$dep_conf("C4GENERATOR_MAIN")));
my $src_list = join"\n", grep{!m"/c4gen-[^/]+$"} &$find_files(map{"$src_dir/$_"}@src_dirs,@pub_dirs);
#
&$if_changed("$tmp/bloop-conf-in-sum",&$get_sum("$dep_content\n$src_list"), sub{
    my $externals = [&$distinct(&$to(&$dep_conf("C4EXT")))];
    my @repo_args = sort map{"-r $_"} &$distinct(&$to(&$dep_conf("C4REPO")));
    my $dependencies_args = join " ", @repo_args, sort @$externals;
    my $coursier_out_path = &$need_path("$tmp/coursier-out.json");
    &$if_changed("$tmp/coursier-in-sum",&$get_sum($dependencies_args), sub{
        sy("coursier fetch -j $coursier_out_path $dependencies_args");
    });
    my $coursier_out = &$json()->decode(&$get_text($coursier_out_path));
    my ($bloop_will,$src_dirs_by_name) = &$calc_bloop_conf($src_dir,$tmp,$dep_conf,$coursier_out,$src_list);
    &$put_text(&$need_path($$_{fn}),$$_{content}) for @$bloop_will;
    &$put_text(&$need_path("$tmp/generator-src-dirs"), join " ", &$src_dirs_by_name($gen_mod));
    &$put_text("$src_dir/c4gen-generator.sbt", &$calc_sbt_conf(\@src_dirs,$externals));
    &$put_text(&$need_path("$tmp/gen/src"),$src_list);
});
#

&$if_changed("$tmp/compile.pl",q^open FF,'|-','bloop','compile',@ARGV; close FF or die;^,sub{}); ### bloop returns 0-exit-code if interrupted with ^C SIGINT; perl during 'system' will not fail on ^C; so we use 'open'
my $sum = &$get_sum(join"\n",map{&$get_text($_)} sort grep{/\.scala$/} &$find_files(&$get_text("$tmp/generator-src-dirs")=~/(\S+)/g));
&$if_changed("$tmp/generator-src-sum",$sum,sub{
    sy("cd $src_dir && perl $tmp/compile.pl $gen_mod");
});
print "generation starting\n";
my $main = &$single(&$from(&$dep_conf("C4GENERATOR_MAIN")));
sy(". $tmp/mod.$gen_mod.classpath.sh && C4GENERATOR_VER=$sum C4GENERATOR_PATH=$tmp/gen java $main");
print "generation finished\n";
print "generating code with perl\n";
my $by = sub{ m^/c4gen-base\b^ ? "ft-c4gen-base" : "ft-other" };
my @src_fns = &$find_files(map{"$src_dir/$_"}@src_dirs);
my @app_traits_will = &$gen_app_traits($src_dir,\@src_fns,[&$dep_conf("C4DEP")]); #after scalameta
&$apply_will($by,\@src_fns,"ft-c4gen-base",[@app_traits_will]);
print "generation finished\n";
do{
    my @dirs = grep{$_} &$to(&$dep_conf("C4CLIENT"));
    for my $path(@dirs){
        my $sum_key = &$get_sum($path);
        my $sum_val = &$get_sum(&$get_text("$path/package.json"));
        &$if_changed("$tmp/client-dep-sum.$sum_key", $sum_val, sub{
            sy("cd $path && npm install");
        });
    }
    my $files = join " ", &$find_files(map{"$_/src"} @dirs), sort grep{-f $_} map{<$_/*>} @dirs;
    sy("md5sum $files > $tmp/client-sums");
};
&$put_text(&$need_path("$src_dir/target/gen-ver"),time);
