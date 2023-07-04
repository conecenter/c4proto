
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

my $changing = sub{
    my($path,$will,$then)=@_;
    return 0 if (-e $path) && &$get_text($path) eq $will;
    &$need_path($path);
    $then && &$then(); # we need to run $then here -- if it fails, state will remain unchanged
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

my $to_parent = sub{ map{ m{^(.+)/[^/]+$} ? ("$1"):() } @_ };

###
my $src_dir = syf("pwd")=~/^(\S+)\s*$/ ? $1 : die;
my $tmp = "$src_dir/target/c4";
my $proto_dir = &$single(&$to_parent("$0"));
sy("python3","$proto_dir/build.py",$src_dir);
my $build_data = &$json()->decode(&$get_text("$tmp/build.json"));
my ($dep_conf) = &$group(map{ my($tp,$from,$to)=@$_; [$tp,[$from,$to]] } grep{ref} @{$$build_data{plain}||die});

#grep{!m"/c4gen-[^/]+$"}
&$changing("$tmp/paths.json", &$json()->encode(&$calc_main_paths($src_dir,$tmp,$dep_conf)), 0);
do{
    my $gen_mod = &$single(&$to(&$dep_conf("C4GENERATOR_MAIN")));
    print "generation starting\n";
    my $sum = &$get_sum(
        join"\n", map{&$get_text($_)} sort grep{/\.scala$/}
        &$find_files(map{"$src_dir/$_"}@{&$json()->decode(&$get_text("$tmp/mod.$gen_mod.d/c4sync_paths.json"))})
    );
    &$changing("$tmp/generator-src-sum",$sum,sub{
        sy("cd $src_dir && perl $proto_dir/compile.pl $gen_mod");
    });
    my $main = &$single(&$from(&$dep_conf("C4GENERATOR_MAIN")));
    sy(". $tmp/mod.$gen_mod.classpath.sh && C4GENERATOR_VER=$sum C4GENERATOR_PATH=$src_dir java $main");
    print "generation finished\n";
};

do{
    if(!-e "$tmp/client"){
        sy("cp -r $ENV{HOME}/c4client_prep $tmp/client");
    }
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
