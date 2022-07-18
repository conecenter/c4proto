
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
my $changing = sub{
    my($path,$will)=@_;
    return 0 if (-e $path) && &$get_text($path) eq $will;
    &$need_path($path);
    &$put_text($path,$will);
    1;
};
my $get_sum = do{
    my $ver = "013";
    sub{ md5_hex("${ver}v\n$_[0]") };
};
my $json = sub{ JSON::XS->new->ascii(1)->canonical(1)->pretty(1) };
my $from = sub{ map{ @$_==2 && $$_[0] || die } @_ };
my $to = sub{ map{ @$_==2 && $$_[1] || die } @_ };
#

my $calc_main_paths = sub{
    my($dir,$tmp,$dep_conf) = @_;
    my @main_scala = map{ my($from,$to)=@$_; $from eq "main"?"$dir/$to":()} &$dep_conf("C4SRC");
    my @main_public = map{ my($from,$to)=@$_; $from eq "main"?"$dir/$to":()} &$dep_conf("C4PUB");
    @main_scala<=1 && @main_public<=1 or die;
    my $public_paths =join ":", "$tmp/client/out", @main_public;
    +{
        (map{(C4GENERATOR_MAIN_SCALA_PATH=>$_)} @main_scala),
        (map{(C4GENERATOR_MAIN_PUBLIC_PATH=>$_)} @main_public),
        C4PUBLIC_PATH => $public_paths,
    }
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
    my($put,@tasks)=@_;
    for(@tasks){
        if(@$_==1){
            print "deleting $$_[0]\n";
            unlink $$_[0] or die $$_[0];
        } elsif(@$_==2) {
            print "putting $$_[0]\n";
            &$put(@$_);
        }
    }
};
my $apply_will = sub{
    my($by,$fns,$gr,$will)=@_;
    my @was = &$read(grep{&$by() eq $gr} @$fns);
    die @$_ for map{&$by() eq $gr ? ():[$gr,$_]} map{$$_{fn}} @$will;
    &$apply_gen_tasks($put_text,&$get_gen_tasks(\@was,$will));
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
my $tmp = "$src_dir/target/c4";
my $proto_dir = &$single(&$to_parent("$0"));
sy("python3 $proto_dir/build.py");
my $dep_content = &$get_text("$tmp/build.json");
my $build_data = &$json()->decode($dep_content);
my ($dep_conf) = &$group(map{ my($tp,$from,$to)=@$_; [$tp,[$from,$to]] } grep{ref} @{$$build_data{plain}||die});
my @src_dirs = &$distinct(&$to(&$dep_conf("C4SRC")));
my @pub_dirs = &$distinct(&$to(&$dep_conf("C4PUB")));
my $gen_mod = &$single(&$to(&$dep_conf("C4GENERATOR_MAIN")));
my $src_list = [grep{!m"/c4gen-[^/]+$"} &$find_files(map{"$src_dir/$_"}@src_dirs,@pub_dirs)];
my $need_update = &$changing("$tmp/build-json-sum",&$get_sum($dep_content));
my $paths = &$calc_main_paths($src_dir,$tmp,$dep_conf);
&$changing("$tmp/paths.json", &$json()->encode($paths));
my %is_off_dir = map{($_=>1)} map{"$src_dir/$_"}@{$$build_data{src_dirs_generator_off}||[]};
&$changing("$tmp/gen/src", join"\n",grep{ m"/c4gen\.[^/]+$" || !(grep{$is_off_dir{$_}}&$to_parent($_)) }@$src_list);

# handle  $need_update
#restore wartremover
# C4EXCL?
# check ^C

do{
    print "generation starting\n";
    my $sum = &$get_sum(join"\n",map{&$get_text($_)} sort grep{/\.scala$/}
        &$find_files(map{"$src_dir/$_"}@{$$build_data{src_dirs_by_tag}{$gen_mod}||die}));
    &$changing("$tmp/generator-src-sum",$sum)
        and sy("cd $src_dir && perl $proto_dir/compile.pl $gen_mod");
    my $main = &$single(&$from(&$dep_conf("C4GENERATOR_MAIN")));
    sy(". $tmp/mod.$gen_mod.classpath.sh && C4GENERATOR_VER=$sum C4GENERATOR_PATH=$tmp/gen java $main");
    print "generation finished\n";
};
do{
    print "generating code with perl\n";
    my $by = sub{ m^/c4gen-base\b^ ? "ft-c4gen-base" : "ft-other" };
    my @src_fns = &$find_files(map{"$src_dir/$_"}@src_dirs);
    my @app_traits_will = &$gen_app_traits($src_dir,\@src_fns,[&$dep_conf("C4DEP")]); #after scalameta
    &$apply_will($by,\@src_fns,"ft-c4gen-base",[@app_traits_will]);
    print "generation finished\n";
};
do{
    my @parts = &$dep_conf("C4CLIENT");
    my @dirs = grep{$_} &$to(@parts);
#    for my $path(@dirs){
#        my $conf_path = "$path/package.json";
#        -e $conf_path or next;
#        my $sum_key = &$get_sum($path);
#        my $sum_val = &$get_sum(&$get_text($conf_path));
#        &$if_changed("$tmp/client-dep-sum.$sum_key", $sum_val, sub{
#            sy("cd $path && npm install");
#        });
#    }
    my $files = join " ", &$find_files(map{"$_/src"} @dirs), sort grep{-f $_} map{<$_/*>} @dirs;
    sy("md5sum $files > $tmp/client-sums");
    #
    my $bs_dir = "$tmp/client/src";
    my @was = map{+{ fn=>$_, content=>readlink($_) }} <$bs_dir/*>;
    my @will = map{
        my $f = &$single(&$from($_));
        my $t = &$single(&$to($_));
        +{ fn=>"$bs_dir/$f", content=>"$src_dir/$t/src" }
    } @parts;
    my @tasks = &$get_gen_tasks(\@was,\@will);
    &$apply_gen_tasks(sub{
        my($l,$f)=@_;
        unlink $l;
        symlink $f, &$need_path($l) or die "$!: $f, $l";
    },@tasks);
};
&$put_text(&$need_path("$src_dir/target/gen-ver"),time);
