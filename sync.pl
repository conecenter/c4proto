
use strict;
use Time::HiRes;

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }

my $single = sub{ @_==1 ? $_[0] : die };

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

my $pod_path = "/tmp/c4pod";
my $dev_context = "dev";

my $put_bin = sub{
    my($nm,$content)=@_;
    my $bin_path = "$ENV{HOME}/bin";
    my $path = "$bin_path/$nm";
    return if -e $path and &$get_text($path) eq $content;
    sy("mkdir -p $bin_path");
    &$put_text($path,$content);
    sy("chmod +x $path");
};

my $setup_rsh = sub{
    my ($user) = @_;
    my $perl_exec = sub{ join"\n",'#!/usr/bin/perl','use strict;',@_,'die;' };
    &$put_bin("kc",&$perl_exec('exec "kubectl", "--context", @ARGV;'));
    &$put_bin("c4rsh",&$perl_exec(
        'my ($pod,@args) = @ARGV ? @ARGV : `cat '.$pod_path.'`||die "no pod";',
        'my ($mode,@e_args) = @args==0 ? ("-it","bash") : @args==1 && $args[0]=~/\s/ ? ("-i","sh","-c",@args) : ("-i",@args);',
        'exec "kc", "'.$dev_context.'", "exec", $mode, $pod, "--", @e_args;',
    ));
    my $proto_dir = $ENV{C4CI_PROTO_DIR} || die;
    &$put_bin("c4forward",&$perl_exec('exec "perl","'.$proto_dir.'/sync.pl","forward","'.$user.'",@ARGV;'));
};

my $list_pods = sub{
    my ($user) = @_;
    my $kc_get = "kc $dev_context get";
    my $jsonpath_names = '-o jsonpath="{.items[*].metadata.name}"';
    my $find_names = sub{ my($v)=@_; $v=~/(\S+)/g };
    map{ &$find_names(syf("$kc_get po -l app=$_ $jsonpath_names")) }
        grep{/-main$/} &$find_names(syf("$kc_get deploy -l c4env_group=de-$user $jsonpath_names"))
};

my $do_forward = sub{
    my $pod = &$single(@_);
    &$put_text($pod_path,$pod);
    print "$pod selected\n";
};

my $auto_pod = sub{
    my ($user) = @_;
    for my $path(grep{-e $_} $pod_path){
        my $pod = &$get_text($path);
        so("kc $dev_context wait --for=delete pod/$pod");
        so("kc $dev_context get pod/$pod") or return;
    }
    print "need c4forward ...\n";
    my @pods = &$list_pods($user);
    @pods==1 ? &$do_forward(@pods) : sleep 3;
};

my $forward = sub{
    my ($user,@args) = @_;
    my @pods = &$list_pods($user);
    if(@args==0){
        print "c4forward $_\n" for @pods;
    } else {
        my $pod_arg = &$single(@args);
        &$do_forward(grep{ $pod_arg eq $_ }@pods);
    }
};

my $get_remote_pre = sub{ "c4rsh ".&$get_text($pod_path)." " };

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
    sy("rsync --blocking-io -e c4rsh -rltDvc --files-from=".&$put_temp(upd=>&$lines(@upd))." $from_pre_d$from_dir/ $to_pre_d$to_dir") if @upd;
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
    for(grep{m"\bc4gen\b|/target/|/tmp/|/node_modules/|/.bloop/"} map{"/$_"} &$find("","$dir/",[])){
        my $path = "$dir$_";
        print "deleting $path\n";
        unlink $path or die;
    }
}];
push @tasks, ["setup_rsh","",sub{
    my ($user) = @_;
    &$setup_rsh($user);
    &$auto_pod($user) while 1;
}];
push @tasks, ["forward","",sub{&$forward(@_)}];
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
    my @changed = grep{/\bc4gen\b/ || /-generated\./} &$find($remote_pre,"$remote_dir/",$prune);
    my %changed = map{($_=>1)} @changed;
    &$sync0($dir,$remote_dir,1,\@changed,sub{$changed{$_[0]}?"upd":"del"});
}];
push @tasks, ["run","",sub{
    my($dir,$cmd)=@_;
    my $remote_dir = &$request_remote_dir();
    my $remote_pre = &$get_remote_pre();
    sy("$remote_pre 'cd $remote_dir && $cmd'");
}];

$| = 1;
my($cmd,@args)=@ARGV;
($cmd||'') eq $$_[0] and $$_[2]->(@args) for @tasks;
