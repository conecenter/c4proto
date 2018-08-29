#!/usr/bin/perl

use strict;
use Digest::MD5 qw(md5_hex);

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ print "$_\n" and return scalar `$_` for @_ }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my @tasks;

my $composes = require "$ENV{C4DEPLOY_CONF}/deploy_conf.pl";
my $ssh_add  = sub{"ssh-add $ENV{C4DEPLOY_CONF}/id_rsa"};
my $composes_txt = "(".(join '|', sort keys %$composes).")";

my $gen_ip = sub{
    join ".", 127, map{hex} md5_hex($_[0]||die)=~/(..)(..)(..)/ ? ($1,$2,$3) : die
};

my $get_host_port = sub{grep{$_||die}@{($$composes{$_[0]}||die "composition expected")}{qw(host port dir)}};

my $ssh_ctl = sub{
    my($comp,$args)=@_;
    my ($host,$port,$dir) = &$get_host_port($comp);
    "ssh c4\@$host -p $port $args";
};
my $remote = sub{ 
    my($comp,$stm)=@_;
    my ($host,$port,$dir) = &$get_host_port($comp);
    $stm = &$stm("$dir/$comp") if ref $stm;
    "ssh c4\@$host -p $port '$stm'";
};

push @tasks, ["ssh", $composes_txt, sub{
    sy(&$ssh_add());
    sy(&$ssh_ctl($_[0],""))
}];

push @tasks, ["git_init", "<proj> $composes_txt-<service>", sub{
    my($proj,$app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = $app=~/^(\w+)-(\w+)$/ ? ($1,$2) : die $app;
    my ($host,$port,$ddir) = &$get_host_port($comp);
    my $repo = "$ddir/$comp/$service";
    my $ip = &$gen_ip($comp);
    #
    so(&$remote($comp,"mv $repo ".rand()));
    #
    my $git = "cd $repo && git ";
    my $hook = "$repo/.git/hooks/post-update";
    sy(&$remote($comp,"mkdir -p $repo"));
    sy(&$remote($comp,"touch $repo/.dummy"));
    sy(&$remote($comp,"$git init"));
    sy(&$remote($comp,"$git config receive.denyCurrentBranch ignore"));
    sy(&$remote($comp,"$git config user.email deploy\@cone.ee"));
    sy(&$remote($comp,"$git config user.name deploy"));
    sy(&$remote($comp,"$git add .dummy"));
    sy(&$remote($comp,"$git commit -am-"));
    sy(&$remote($comp,"echo \"cd .. && unset GIT_DIR && git reset --hard && exec sh +x compose-up.sh\" > $hook"));
    sy(&$remote($comp,"chmod +x $hook"));
    #
    my $bdir = "$ENV{C4DEPLOY_CONF}/$proj";
    my $adir = "$bdir/$app.adc";
    my $git_dir = "$bdir/$app.git";
    my $tmp = "$bdir/tmp";
    my $cloned = "$tmp/$service";
    #
    -e $_ or mkdir $_ or die $_ for $adir, $tmp;
    !-e $_ or rename $_, "$tmp/".rand() or die $_ for $git_dir, $cloned;
    #
    my $img_pre = "$ip:5000/c4-";
    my $comp_img = $img_pre."composer";
    my $from_img = $img_pre."zoo";
    &$put_text("$adir/vconf.json",'{"git.postCommit" : "push"}');
    &$put_text("$adir/.dockerignore",".dockerignore\nDockerfile\n.git\ncompose-up.sh");
    &$put_text("$adir/Dockerfile","FROM $from_img\nCOPY . /c4\nCMD sh start.run\n");
    &$put_text("$adir/compose-up.sh", join "", map{"$_ || exit\n"}
        "docker pull $comp_img",
        "docker run --rm --userns=host "
        ." -v /var/run/docker.sock:/var/run/docker.sock "
        ." -v \$HOME/$ddir/$comp:/c4deploy "
        ." $comp_img up $comp main/docker-compose.yml",
    );
    sy("cd $tmp && git clone ssh://c4\@$host:$port/~/$repo");
    sy("mv $cloned/.git $git_dir");
    #my $post_commit = "$git_dir/hooks/post-commit";
    #&$put_text($post_commit,"git push");
    #sy("chmod +x $post_commit");
}];

push @tasks, ["run_registry", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $ip = &$gen_ip($comp);
    sy(&$remote($comp,"curl $ip:5000 || docker run -d -p $ip:5000:5000 --restart=unless-stopped --name $comp-registry registry:2"));
}];
push @tasks, ["ssh_registry", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $ip = &$gen_ip($comp);
    sy(&$ssh_ctl($comp," -L $ip:5000:$ip:5000 "));
}];

my $list_snapshots = sub{
    my($comp,$opt)=@_;
    my $ls = &$remote($comp,"docker exec -u0 $comp\_snapshot_maker_1 ls $opt db4/snapshots");
    print "$ls\n";
    `$ls`;
};

my $get_snapshot = sub{
    my($comp,$snnm)=@_;
    my @fn = $snnm=~/^(\w{16})(-\w{8}-\w{4}-\w{4}-\w{4}-\w{12})\s*$/ ? ($1,$2) : die;
    my $zero = '0' x length $fn[0];
    sy(&$remote($comp,"docker exec -u0 $comp\_snapshot_maker_1 cat db4/snapshots/$fn[0]$fn[1]")." > $zero$fn[1]");
};

push @tasks, ["list_snapshots", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    print &$list_snapshots($comp,"-la");
}];

push @tasks, ["get_snapshot", "$composes_txt <snapshot>", sub{
    my($comp,$snnm)=@_;
    sy(&$ssh_add());
    &$get_snapshot($comp,$snnm);
}];

push @tasks, ["get_last_snapshot", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $snnm = (reverse sort &$list_snapshots($comp,""))[0];
    &$get_snapshot($comp,$snnm);
}];

my $running_containers = sub{
    my($comp)=@_;
    my $ps_stm = &$remote($comp,'docker ps --format "table {{.Names}}"');
    my ($names,@ps) = syf($ps_stm)=~/(\w+)/g;
    $names eq "NAMES" or die;
    grep{ 0==index $_,"$comp\_" } @ps;
};

my $stop = sub{
    my($comp)=@_;
    my $sshd = "$comp\_sshd_1";
    ## stop all but sshd
    for(0){
        my @ps = grep{$_ ne $sshd} &$running_containers($comp);
        @ps or next;
        sy(&$remote($comp,"docker stop ".join " ",@ps)); 
        sleep 1;
        redo;
    }
};


push @tasks, ["put_snapshot", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $sshd = "$comp\_sshd_1";
    my $remote_sshd  = sub{ &$remote($comp,qq[docker exec -i $sshd sh -c "$_[0]"]) };
    &$stop($comp);
    ## move db to bak
    my $db = "/c4/db4";
    my $bak = "$db/bak.".time;
    my $ls_stm = &$remote_sshd("ls $db");
    my $ls = sub{ grep{!/^bak\./} syf($ls_stm)=~/(\S+)/g };
    sy(&$remote_sshd("mkdir $bak"));
    sy(&$remote_sshd("mv $db/$_ $bak/$_")) for &$ls();
    die $_ for &$ls();
    ## upload snapshot
    my @snapshots = grep{/^0+-/} <*>;
    @snapshots == 1 or die "not a single snapshot";
    my $snapdir = "$db/snapshots";
    sy(&$remote_sshd("mkdir $snapdir && cat > $snapdir/$snapshots[0]")." < $snapshots[0]");
    sy(&$remote_sshd("chown -R c4:c4 $snapdir"));
}];

push @tasks, ["clear_snapshots", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    my $remote_sm = sub{ &$remote($_[0],qq[docker exec -u0 $_[0]_snapshot_maker_1 $_[1]]) };
    my $cmd = &$remote_sm($comp,'find db4/snapshots -printf "%A@ %p\n"');
    print "$cmd\n";
    my @lines = reverse sort `$cmd`;
    my @snaps = map{ m[^(\d{10})\.\d+\s(db4/snapshots/\w{16}-\w{8}-\w{4}-\w{4}-\w{4}-\w{12})\s*$] ? [$1,$2] : () } @lines;
    my @old = sub{@_[20..$#_]}->(@snaps);
    my %byday;
    push @{$byday{sub{sprintf "%04d-%02d-%02d",$_[5]+1900,$_[4]+1,$_[3]}->(gmtime($$_[0]))}||=[]}, $$_[1] for @old;
    for my $date(sort keys %byday){
        my $paths = $byday{$date}||die;
        sy(&$remote_sm($comp,"tar -czf db4/snapshots/.arch-$date.tar.gz $$paths[0]"));
        sy(&$remote_sm($comp,"rm ".join(' ',@$paths)));
    }
}];

push @tasks, ["gc","$composes_txt",sub{
    my($comp)=@_;
    sy(&$ssh_add());
    for my $c(&$running_containers($comp)){
        #print "container: $c\n";
        my $cmd = &$remote($comp,"docker exec $c jcmd");
        for(`$cmd`){
            my $pid = /^(\d+)/ ? $1 : next;
            /JCmd/ && next;
            sy(&$remote($comp,"docker exec $c jcmd $pid GC.run"));
        }
    }
}];

push @tasks, ["stop", $composes_txt, sub{
    my($comp)=@_;
    sy(&$ssh_add());
    &$stop($comp);
}];

my $remote_single_cmd = sub{
    my($app, $cmds)=@_;
    my($comp,$service) = $app=~/^(\w+)-(\w+)$/ ? ($1,$2) : die $app;
    sy(&$remote($comp,sub{"cd $_[0]/$service && $cmds"}));
};

push @tasks, ["start","$composes_txt",sub{
    my($comp)=@_;
    &$ssh_add();
    &$remote_single_cmd("$comp-main","sh compose-up.sh");
}];

push @tasks, ["revert_list","$composes_txt-<service>",sub{
    my($app)=@_;
    &$ssh_add();
    &$remote_single_cmd($app,'git log --format=format:"%H  %ad  %ar  %an" --date=local --reverse');
    print "\n";
}];
push @tasks, ["revert_to","$composes_txt-<service> <commit>",sub{
    my($app,$commit)=@_;
    &$ssh_add();
    my $time = time;
    &$remote_single_cmd($app,"git checkout $commit -b $commit-$time && sh compose-up.sh");
}];
push @tasks, ["revert_off","$composes_txt-<service>",sub{
    my($app)=@_;
    &$ssh_add();
    &$remote_single_cmd($app,"git checkout master && sh compose-up.sh");
}];


if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[2]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks;
}

#userns_mode: "host"
