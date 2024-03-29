
use strict;
use Time::HiRes;

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }

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

my $need_path = sub{
    my($dn)=@_;
    -d $dn or sy("mkdir -p $dn") if $dn=~s{/[^/]*$}{};
    $_[0];
};

my $tmp_root = "/tmp/c4sync";
my $get_tmp_path_inner = sub{ my($fn)=@_; &$need_path("$tmp_root/$$/$fn") };
my $put_temp = sub{
    my($fn,$text)=@_;
    my $path = &$get_tmp_path_inner($fn);
    &$put_text($path,$text);
    print "generated $path\n";
    $path;
};

my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

my $to_rel = sub{
    my($pre,$fn)=@_;
    $pre eq substr $fn, 0, length $pre or die "[$pre][$fn]";
    substr $fn, length $pre;
};

my $find = sub{
    my($pre,$from,$prune)=@_;
    my $prune_str = join'', map{" -o -name '$_' -prune"} @$prune;
    my %prune_h = map{($_=>1)} @$prune;
    map{ &$to_rel($from,$_)=~m{(.+/|)([^/]+)\n} && !$prune_h{$2} ? "$1$2" : () }
        sort `$pre 'find $from -type f$prune_str'`;
};

my $pod_path = "/tmp/c4pod";

my $get_remote_pre = sub{ "c4rsh_raw ".&$get_text($pod_path)." sh -c " };

my $prune = [qw(target .git .idea .bloop node_modules build)];

my $lines = sub{join"",map{"$_\n"}@_};

my $request_remote_dir = sub{
    my $remote_pre = &$get_remote_pre();
    syf("$remote_pre 'echo \$C4CI_BUILD_DIR'")=~/^(\S+)\s*$/ ? "$1" : die;
};

my $distinct_sorted = sub{ sort keys %{+{map{($_=>1)}@_}} };
my $group_by = sub{ my($f,@in)=@_; my %r; push @{$r{&$f($_)}||=[]},$_ for @in; %r };

my $sync0 = sub{
    my($dir,$remote_dir,$is_back,$paths,$get_mode)=@_;
    my $remote_pre = &$get_remote_pre();
    my $remote_pre_d = &$get_text($pod_path).":";
    my($from_pre_d,$from_dir,$to_pre_d,$to_dir,$to_pre) = $is_back ?
        ($remote_pre_d,$remote_dir,"",$dir,"sh -c ") :
        ("",$dir,$remote_pre_d,$remote_dir,$remote_pre);
    my $changed_fn = "target/c4sync-changed";
    my @was = syf("$to_pre 'cd $to_dir && touch $changed_fn && cat $changed_fn'") =~ /(\S+)/g;
    my @all = &$distinct_sorted(@$paths, @was);
    my %task_by_mode = &$group_by($get_mode,@all);
    my @upd = @{$task_by_mode{upd}||[]};
    my @del = @{$task_by_mode{del}||[]};
    my $tm = Time::HiRes::time();
    sy("$to_pre 'cd $to_dir && cat > $changed_fn' < ".&$put_temp(changed=>&$lines(@all)));
    sy("c4dsync -rltDvc --files-from=".&$put_temp(upd=>&$lines(@upd))." $from_pre_d$from_dir/ $to_pre_d$to_dir") if @upd;
    if(@del){
        my $remover_fn = "target/c4sync-rm.pl";
        sy("$to_pre 'cd $to_dir && cat > $remover_fn' < ".&$put_temp(remover=>&$lines(
            q[for(<STDIN>){],
            q[  chomp;],
            q[  next if !-e $_;],
            q[  print "removing $_\n";],
            q[  unlink $_ or die "can not remove ($_)";],
            q[}],
        )));
        sy("$to_pre 'cd $to_dir && perl $remover_fn' < ".&$put_temp(removed=>&$lines(@del)));
    }
    print Time::HiRes::time()-$tm," for rsync+\n";
};

my @tasks;

push @tasks, ["clean_local","",sub{
    my($dir)=@_;
    $dir || die;
    for(grep{m"\bc4gen\b|\bc4msg\b|/target/|/tmp/|/node_modules/|/.bloop/"} map{"/$_"} &$find("sh -c ","$dir/",[])){ # will not clear dir-only trees
        my $path = "$dir$_";
        print "deleting $path\n";
        unlink $path or die;
    }
}];
push @tasks, ["report_changes","",sub{
    my($dir)=@_; $dir || die "need dir";
    my $changed_path = &$mandatory_of(C4GIT_CHANGED_PATH=>\%ENV);
    my $remote_dir = &$request_remote_dir();
    my $remote_pre = &$get_remote_pre();
    &$put_text($changed_path, &$lines(map{ #... to ENV
        my ($dir_infix,$commit) = /^(.*):(.*)$/ ? ($1,$2) : die;
        map{"$dir_infix$_"}
            map{ syf("cd $_ && git diff --name-only --no-renames $commit && git ls-files --others --exclude-standard")=~/(\S+)/g }
                grep{ -e "$_.git" } "$dir/$dir_infix"
    } syf("$remote_pre 'cat $remote_dir/target/c4repo_commits'")=~/(\S+)/g));
}];
push @tasks, ["start","",sub{
    my($dir)=@_; $dir || die "need dir";
    my $changed_path = &$mandatory_of(C4GIT_CHANGED_PATH=>\%ENV);
    my $remote_dir = &$request_remote_dir();
    my @changed = &$get_text($changed_path)=~/(\S+)/g;
    &$sync0($dir,$remote_dir,0,\@changed,sub{(-e "$dir/$_[0]")?"upd":"del"});
}];
push @tasks, ["back","",sub{
    my($dir)=@_;
    my $remote_dir = &$request_remote_dir();
    my $remote_pre = &$get_remote_pre();
    my @changed = grep{/\bc4gen\b/ || /\bc4msg\b/ || /-generated\./} &$find($remote_pre,"$remote_dir/",$prune);
    my %changed = map{($_=>1)} @changed;
    &$sync0($dir,$remote_dir,1,\@changed,sub{$changed{$_[0]}?"upd":"del"});
}];
push @tasks, ["run","",sub{
    my($dir,$cmd)=@_;
    my $remote_dir = &$request_remote_dir();
    my $remote_pre = &$get_remote_pre();
    sy("$remote_pre 'cd $remote_dir && export KUBECONFIG=\$C4KUBECONFIG && $cmd'");
}];

$| = 1;
my($cmd,@args)=@ARGV;
($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
