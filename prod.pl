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

my $split_app = sub{
    my($app)=@_;
    $app=~/^(\w+)-(\w+)$/ ? ($1,$2) : die "<stack>-<service> expected ($app)"
};

push @tasks, ["git_init", "<proj> $composes_txt-<service>", sub{
    my($proj,$app)=@_;
    sy(&$ssh_add());
    my($comp,$service) = &$split_app($app);
    my ($host,$port,$ddir) = &$get_host_port($comp);
    my $repo = "$ddir/$comp/$service";
    #
    so(&$remote($comp,"mv $repo ".rand()));
    #
    my $git = "cd $repo && git ";
    sy(&$remote($comp,"mkdir -p $repo"));
    sy(&$remote($comp,"touch $repo/.dummy"));
    sy(&$remote($comp,"$git init"));
    sy(&$remote($comp,"$git config receive.denyCurrentBranch ignore"));
    sy(&$remote($comp,"$git config user.email deploy\@cone.ee"));
    sy(&$remote($comp,"$git config user.name deploy"));
    sy(&$remote($comp,"$git add .dummy"));
    sy(&$remote($comp,"$git commit -am-"));
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
    &$put_text("$adir/vconf.json",'{"git.postCommit" : "push"}');
    sy("cd $tmp && git clone ssh://c4\@$host:$port/~/$repo");
    sy("mv $cloned/.git $git_dir");
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

my $restart = sub{
    my($app,$cmds)=@_;
    my($comp,$service) = &$split_app($app);
    my $container = "$comp\_$service\_1";
    sy(&$remote($comp,sub{"cd $_[0]/$service && git reset --hard $cmds && docker restart $container && docker logs $container -ft --tail 2000"}));
};

my $remote_single_cmd = sub{
    my($app, $cmds)=@_;
    my($comp,$service) = &$split_app($app);
    sy(&$remote($comp,sub{"cd $_[0]/$service && $cmds"}));
};

push @tasks, ["restart","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$remote_single_cmd($app,"git reset --hard");
    &$restart($app,"");
}];

push @tasks, ["revert_list","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$remote_single_cmd($app,'git log --format=format:"%H  %ad  %ar  %an" --date=local --reverse');
    print "\n";
}];
push @tasks, ["revert_to","$composes_txt-<service> <commit>",sub{
    my($app,$commit)=@_;
    sy(&$ssh_add());
    my $time = time;
    &$restart($app," && git checkout $commit -b $commit-$time");
}];
push @tasks, ["revert_off","$composes_txt-<service>",sub{
    my($app)=@_;
    sy(&$ssh_add());
    &$restart($app," && git checkout master");
}];

#### composer

use List::Util qw(reduce);
use YAML::XS qw(LoadFile DumpFile Dump);
$YAML::XS::QuoteNumericStrings = 1;

my $inbox_prefix = '';
my $bin = "kafka/bin";

my $bootstrap_server = "broker:9092";
my @c_script = ("inbox_configure.pl","purger.pl");
my $user = "c4";

# pass src commit
# migrate states
# >2 >4
# fix kafka configs
# move settings to scala

my %merge;
my $merge = sub{&{$merge{join "-",map{ref}@_}||sub{$_[$#_]}}};
$merge{"HASH-HASH"} = sub{
    my($b,$o)=@_;
    +{map{
        my $k = $_;
        ($k=>&$merge(map{(exists $$_{$k})?$$_{$k}:()} $b,$o));
    } keys %{+{%$b,%$o}}};
};
$merge{"ARRAY-ARRAY"} = sub{[map{@$_}@_]};

my $extract_env = sub{
    my($opt) = @_;
    my %env = map{/^C4/?($_=>$$opt{$_}):()} keys %$opt;
    my %def = map{/^C4/?():($_=>$$opt{$_})} keys %$opt;
    &$merge({environment => \%env}, \%def);
};

my $app_user = sub{
    my %opt = @_;
    (user=>$user, working_dir=>"/$user");
};

my $volumes = sub{(volumes => [map{"$_:/$user/$_"}@_])};

my $template_yml = sub{+{
    services => {
        zookeeper => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["$bin/zookeeper-server-start.sh","zookeeper.properties"],
            &$volumes("db4"),
        },
        broker => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["$bin/kafka-server-start.sh","server.properties"],
            depends_on => ["zookeeper"],
            &$volumes("db4"),
        },
        inbox_configure => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["perl",$c_script[0]],
            depends_on => ["broker"],
        },
        gate => {
            C4APP_IMAGE => "gate-server",
            C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.HttpGatewayApp",
            C4STATE_REFRESH_SECONDS => 100,
        },
        snapshot_maker => {
            C4APP_IMAGE => "gate-server",
            C4STATE_TOPIC_PREFIX => "ee.cone.c4gate.SnapshotMakerApp",
            #restart => "on-failure",
        },
        purger => {
            &$app_user(),
            C4APP_IMAGE => "zoo",
            command => ["perl",$c_script[1]],
            tty => "true",
            &$volumes("db4"),
        },
        sshd => {
            C4APP_IMAGE => "sshd",
            command => ["/usr/sbin/sshd", "-D"],
            &$volumes("db4"),
            expose => [22],
        },
        haproxy => {
            C4APP_IMAGE => "haproxy",
            expose => [80],
        }
    },
    volumes => { db4 => {} },
    version => "3.2",
}};

my $remote_build = sub{
    my($build_comp,$dir,$nm,$tag)=@_;
    my $build_temp = syf("hostname")=~/(\w+)/ ? "c4build_temp/$1" : die;
    my $rsync_to = sub{
        my($from_path,$comp,$to_path)=@_;
        my ($host,$port,$dir) = &$get_host_port($comp);
        "rsync -e 'ssh -p $port' -a $from_path $user\@$host:$to_path";
    };
    sy(&$remote($build_comp,"mkdir -p $build_temp"));
    sy(&$rsync_to("$dir/$nm",$build_comp,$build_temp));
    sy(&$remote($build_comp,"docker build -t $tag $build_temp/$nm"));
};


push @tasks, ["compose_up","<builder> $composes_txt [options]",sub{
    my($build_comp,$run_comp,@opt)=@_;
    sy(&$ssh_add());

    my $override = reduce{&$merge($a,$b)} &$template_yml(),
        map{LoadFile($_)} grep{/\.yml$/} @opt;
    my $override_services = $$override{services} || {};
    my %was;
    my $generated_services = {map{
        my $service_name = $_;
        my $service = $$override_services{$service_name} || die;
        my $img = $$service{C4APP_IMAGE} || $service_name;
        my $build_parent_dir = (grep{-e "$_/$img/Dockerfile"} @opt)[0];
        my $tag = "c4-$run_comp-$img";
        $build_parent_dir and !($was{$img}++) and &$remote_build($build_comp,$build_parent_dir,$img,$tag);
        ##todo: !$need_commit or `cat $dockerfile`=~/c4commit/ or die "need commit and rebuild";
        my $generated_service = {
            restart=>"unless-stopped",
            ($$service{C4STATE_TOPIC_PREFIX}?(
                &$app_user(),
                depends_on => ["broker"],
                C4BOOTSTRAP_SERVERS => $bootstrap_server,
                C4INBOX_TOPIC_PREFIX => $inbox_prefix,
                &$volumes("db4"),
            ):()),
            image => $tag
        };
        ($service_name => &$extract_env(&$merge($generated_service,$service)));
    } keys %$override_services };
    my $generated = { %$override, services => $generated_services };

    #DumpFile("docker-compose.yml",$generated);
    my $yml_str = Dump($generated);
    #$text=~s/(\n\s+-\s+)([^\n]*\S:\d\d)/$1"$2"/gs;
    $yml_str=~s/\b(tty:\s)'(true)'/$1$2/;
    ##todo: fix need_commit; ...[some.yml]
    my $yml_path = "/tmp/$$-docker-compose.yml";
    &$put_text($yml_path,$yml_str);
    print "generated $yml_path\n";
    if($build_comp ne $run_comp){
        my %images = map{$_?($_=>1):()} map{$$_{image}} values %$generated_services;
        my $images_str = join " ", sort keys %images;
        sy(&$remote($build_comp,"docker save $images_str").' | '.&$remote($run_comp,"docker load"));
    }
    sy(&$remote($run_comp,"docker-compose -f - -p $run_comp up -d --remove-orphans")." < $yml_path");
}];

####

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[2]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{!$$_[1] ? () : "  prod $$_[0] $$_[1]"} @tasks;
}

#userns_mode: "host"
