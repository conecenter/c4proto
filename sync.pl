
use strict;
use Time::HiRes;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

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

my $to_rel = sub{
    my($pre,$fn)=@_;
    $pre eq substr $fn, 0, length $pre or die;
    substr $fn, length $pre;
};

my $find = sub{
    my($pre,$from,$prune)=@_;
    my $cmd = "${pre}find $from";
    my $prune_str = join'', map{" -o -name '$_' -prune"} @$prune;
    my %prune_h = map{($_=>1)} @$prune;
    map{ &$to_rel($from,$_)=~m{(.+/|)([^/]+)\n} && !$prune_h{$2} ? "$1$2" : () }
        sort `$cmd -type f$prune_str`;
};

my $sync = sub{
    my ($list_fn,$ssh,$from,$from_fns,$to,$to_fns) = @_;
    my %keep = map{($_=>1)} @$from_fns;
    my @to_rm = grep{!$keep{$_}} @$to_fns;
    &$put_text($list_fn, join "", map{"$_\n"} @$from_fns);
    &$put_text("$list_fn.rm",join " ", "true", map{$_%10 ? $to_rm[$_] : ("\\\n && rm",$to_rm[$_])} 0..(@to_rm-1));
    my $tm = Time::HiRes::time();
    my $ssh_opt = $ssh ? "-e '$ssh'" : "";
    sy("rsync $ssh_opt -avc --files-from=$list_fn $from/ $to") if @$from_fns; #  | grep -E '^deleting|[^/]\$|^\$'
    print Time::HiRes::time()-$tm," for rsync\n";
};

my $filter = sub{
    my($need_generated,$l)=@_;
    grep{(m{\bc4gen\b}?1:0)==$need_generated} @$l;
};

my $get_list_fn = sub{"$_[0]/target/c4sync"};
my $get_remote = sub{
    my($port_str)=@_;
    my $port = $port_str=~/^(\d+)$/ ? $1-0 : die;
    $port ? ("ssh -p$port","c4\@127.0.0.1:","ssh -p$port c4\@127.0.0.1 "):("","","")
};
my $load_started = sub{
    my($dir)=@_;
    my $list_fn = &$get_list_fn($dir);
    my @res = &$get_text("$list_fn.conf")=~/(.+)/g;
    @res;
};

my $prune = [qw(target .git .idea .bloop node_modules build)];

my $clean_local = sub{
    my($dir)=@_;
    for(grep{m"\bc4gen\b|/target/|/tmp/|/node_modules/|/.bloop/"} map{"/$_"} &$find("","$dir/",[])){
        my $path = "$dir$_";
        print "deleting $path\n";
        unlink $path or die;
    }
};

my @tasks;

push @tasks, ["clean_local","",sub{
    my($dir)=@_;
    $dir || die;
    &$clean_local($dir);
}];
push @tasks, ["start","",sub{
    my($dir,$remote_dir,$port)=@_;
    $dir && $remote_dir || die;
    $port || $dir ne $remote_dir || die "from and to are the same $dir";
    my $clean = $ENV{C4BUILD_CLEAN}-0;
    my($ssh,$remote_pre_d,$remote_pre) = &$get_remote($port);
    my $remote_pre_q = $remote_pre || "sh -c ";
    if($clean){
        &$clean_local($dir);
        sy("$remote_pre_q 'mkdir -p $remote_dir && rm -r $remote_dir'");
    }
    sy("$remote_pre_q 'mkdir -p $remote_dir'");
    -e $_ or mkdir $_ or die "$! -- $_" for "$dir/target";
    my @local_fns = &$find("","$dir/",$prune);
    my $list_fn = &$get_list_fn($dir);
    &$sync($list_fn,$ssh,
        $dir,[&$filter(0,\@local_fns)],
        "$remote_pre_d$remote_dir",[&$filter(0,[&$find($remote_pre,"$remote_dir/",$prune)])]
    );
    sy("$remote_pre_q 'cd $remote_dir && sh' < $list_fn.rm");
    &$put_text("$list_fn.local", join "", map{"$_\n"} @local_fns);
    &$put_text("$list_fn.conf", "$remote_dir\n$port");
    #
    -e "$dir/.git" and sy("cd $dir && git log --pretty=%h | head | $remote_pre_q 'mkdir -p $remote_dir/target && cat > $remote_dir/target/c4git-log'")
}];
push @tasks, ["back","",sub{
    my($dir)=@_;
    my($remote_dir,$port) = &$load_started($dir);
    my($ssh,$remote_pre_d,$remote_pre) = &$get_remote($port);
    my $list_fn = &$get_list_fn($dir);
    &$sync($list_fn,$ssh,
        "$remote_pre_d$remote_dir",[&$filter(1,[&$find($remote_pre,"$remote_dir/",$prune)])],
        $dir,[&$filter(1,[&$get_text("$list_fn.local")=~/(.+)/g])]
    );
    sy("cd $dir && sh < $list_fn.rm");
}];
push @tasks, ["run","",sub{
    my($dir,$cmd)=@_;
    my($remote_dir,$port) = &$load_started($dir);
    my($ssh,$remote_pre_d,$remote_pre) = &$get_remote($port);
    $remote_pre || die;
    sy("$remote_pre '. /c4p_alias.sh && cd $remote_dir && $cmd'");
}];

my($cmd,@args)=@ARGV;
($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;

