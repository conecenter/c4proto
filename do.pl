#!/usr/bin/perl

use strict;
use POSIX ":sys_wait_h";

my $http_server = $ENV{C4HTTP_SERVER}||die 'no C4HTTP_SERVER';
my $curl_test = "curl $http_server/abc";
my $gen_dir = "."; #"target/c4gen/res";

sub sy{
    print "$ENV{PATH}\n";
    print join(" ",@_),"\n"; system @_ and die $?;
}
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }

my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

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
my $need_tmp = sub{ -e $_ or mkdir $_ or die for "tmp" };

my @tasks;
my $main = sub{
    my($cmd,@args)=@_;
    ($cmd||'') eq $$_[0] and $$_[1]->(@args) for @tasks;
};

my $exec_server = sub{
    my($arg)=@_;
    my $argv = $arg=~/\./ ? $arg : &$get_text(".bloop/c4/tag.$arg.to");
    my ($nm,$mod,$cl) = $argv=~/^(\w+)\.(.+)\.(\w+)$/ ? ($1,"$1.$2","$2.$3") : die;
    my $tmp = ".bloop/c4";
    sy("perl $tmp/compile.pl $mod");
    &$exec(". $tmp/mod.$mod.classpath.sh && C4STATE_TOPIC_PREFIX=$nm C4APP_CLASS=$cl exec java ee.cone.c4actor.ServerMain");
};
push @tasks, ["run", sub{
    &$exec_server($_[0])
}];
my %color = qw(bright_red 91 green 32 yellow 33 bright_yellow 93 reset 0);
my $color = sub{
    my $v = $color{$_[0]};
    length $v or die $_[0];
    "\x1b[${v}m"
};
my $colored_line = sub{
    my($color_arg,$content)=@_;
    "\n".&$color($color_arg).$content.&$color('reset')."\n";
};
my $prep_empty_dir = sub{
    my($dir)=@_;
    -e $dir or mkdir $dir or die;
    my @dir_cont = <$dir/*>;
    !@dir_cont or unlink @dir_cont or die;
    $dir;
};
my $keep_only = sub{
    my($list,$pid) = @_;
    my @to_kill = grep{ $_ != $pid } @$list;
    @to_kill or return 0;
    print &$colored_line(bright_yellow=>"Killing: ".join(", ",@to_kill));
    kill 'TERM', @to_kill;
    1;
};

my $debug_port = 5005;
my $debug_proxy = sub{
    my($pre,$debug_ext_address,$debug_int_address)=@_;
    my $ha_cfg_path = "$pre-haproxy.cfg";
    my $ha_pid_path = "$pre-haproxy.pid";
    &$put_text($ha_cfg_path, join "\n",
        "global",
        "  tune.ssl.default-dh-param 2048",
        "defaults",
        "  timeout connect 5s",
        "  timeout client  3d",
        "  timeout server  3d",
        "  mode tcp",
        "listen listen_def",
        "  bind $debug_ext_address",
        "  server s_def $debug_int_address",
    );
    my @ha_pids = (-e $ha_pid_path) ? &$get_text($ha_pid_path)=~/(\d+)/g : ();
    sy("/usr/sbin/haproxy","-D","-f",$ha_cfg_path,"-p",$ha_pid_path,"-sf",@ha_pids);
};
my $get_debug_ip = sub{
    my($pid)=@_;
    "127.1.".(($pid>>8) & 0xFF).".".($pid & 0xFF);
};

push @tasks, ["loop", sub{
    my ($pre,$arg) = @_;
    my $was_ver;
    my @active_pid;
    my $droll = "./target/dev-rolling-";
    $ENV{C4ELECTOR_PROC_PATH} = "/proc/$$";
    my ($debug_ext_address,$debug_port) = !$ENV{C4DEBUG_PROXY} ? (undef,undef) :
        $ENV{C4DEBUG_PROXY}=~/^([\d\.]+:(\d+))$/ ? ($1,$2) : die;
    while(1){
        @active_pid = grep{
            my $res = waitpid($_, WNOHANG);
            if($res != 0){
                #my $code = $? >> 8;
                #my $signal = $? & 127;
                # 10001111_00000000 for SIGTERM
                # 00000011_00000000 for exit(3)
                my $hex = sprintf("%X", $?);
                print &$colored_line(bright_yellow=>"Child $res ended with status 0x$hex");
            }
            $res == 0;
        } @active_pid;
        print "active pid list: @active_pid\n" if @active_pid > 1;
        my $master = (grep{ -e "$droll$_/c4is-master" } @active_pid)[-1];
        my $last_ready = (grep{ -e "$droll$_/c4is-ready" } @active_pid)[-1];
        my $curr_ver = &$get_text("./target/gen-ver");
        #
        if($was_ver ne $curr_ver){
            &$keep_only(\@active_pid,$master) or do {
                $was_ver = $curr_ver;
                my $pid = fork();
                defined $pid or die;
                if(!$pid){
                    my $dir = "$droll$$";
                    &$prep_empty_dir($dir);
                    $ENV{C4ROLLING} = $dir;
                    $ENV{JAVA_TOOL_OPTIONS} = !$debug_port ? $ENV{JAVA_TOOL_OPTIONS} : do{
                        my $debug_int_ip = &$get_debug_ip($$);
                        " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debug_int_ip:$debug_port $ENV{JAVA_TOOL_OPTIONS}";
                    };
                    #
                    sy($pre);
                    &$exec_server($arg);
                    die;
                }
                print &$colored_line(bright_yellow=>"Spawned $pid");
                if($debug_port){
                    my $debug_int_ip = &$get_debug_ip($pid);
                    &$debug_proxy($droll,$debug_ext_address,"$debug_int_ip:$debug_port");
                }
                push @active_pid, $pid;
            };
        } elsif($last_ready && $last_ready != $master){
            &$keep_only(\@active_pid,$last_ready) or &$put_text("$droll$last_ready/c4is-master","");
        }
        sleep 1;
    }
#? say Failed
}];
push @tasks, ["test", sub{
    my @arg = @_;
    print map{
        my $src_dir = $_;
        my $src_mod = $src_dir=~m{([^/]+)/src$} ? $1 : die;
        my $src_files = join(" ", grep{m"/c4gen\.scala$"} `find $src_dir`=~/(\S+)/g) || die;
        map{"\t$0 run $src_mod.$_\n"} map{/(\S+)/g} `cat $src_files`=~/C4APPS:([^\n]+)/g;
    } grep{-e $_} map{"$_/src"} grep{/example/} <$gen_dir/*>;
}];



push @tasks, ["test_client",sub{
    my @arg = @_;
    if(@arg==0){
        print map{$$_[0]=~/^test_client\s/ ? "\t$0 $$_[0]\n":()} @tasks;
    } elsif(@arg==1){
        &$main("test_client $arg[0]")
    } else { die }
}];
push @tasks, ["test_client ee.cone.c4gate.TestConsumerApp", sub{
    my $v = int(rand()*10);
    sy("$curl_test -X POST -d $v");
    sleep 1;
    sy("$curl_test -v");
    sleep 4;
    sy("$curl_test -v");
    print " -- should be posted * 3\n";
}];
push @tasks, ["test_client ee.cone.c4gate.TestParallelApp", sub{
    sy("$curl_test -X POST") for 0..11;
}];
push @tasks, ["test_client post_big_message", sub{
    &$need_tmp();
    sy("dd if=/dev/zero of=tmp/test.bin bs=1M count=4 && $curl_test -v -XPOST -T tmp/test.bin")
}];

push @tasks,["",sub{
    print "usage:\n";
        $$_[0] && $$_[0]!~/\s/ and print "\t$0 $$_[0]\n" for @tasks;
}];

&$main(@ARGV);



#export PATH=$HOME/tools/jdk/bin:$HOME/tools/sbt/bin:$PATH
#sbt show compile:dependencyClasspath
#... ScalaCheck, Specs2, and ScalaTest

# http://localhost:8067/sse.html#
#http://localhost:8067/react-app.html#todo
#http://localhost:8067/react-app.html#rectangle
#http://localhost:8067/react-app.html#leader

# TestCanvasApp C4PUBLISH_DIR=$build_dir need?

#tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh --zookeeper 127.0.0.1:8081 --list

#force compaction:?
#min.cleanable.dirty.ratio=0.01
#segment.ms=100
#delete.retention.ms=100

#tar cvf - db4 | lz4 - db.tar.lz4
#lz4 -d db.tar.lz4 | tar xf -

#push @tasks, ["test_tcp_check", sub{
#    sy("nc 127.0.0.1 $sse_port");
#}];

#topic integrity:
#use strict;
#use JSON::XS;
#my $e = JSON::XS->new;
#my $n = 0;
#my $c = 0;
#while(<>){
#  /records_consumed/ or next;
#  my $j = $e->decode($_);
#  $$j{name} eq "records_consumed" or next;
#  my($count,$min,$max) = @{$$j{partitions}[0]}{qw(count minOffset maxOffset)};
#  $count-1 == $max-$min or die $_;
#  $n == $min or die $_;
#  $n = $max + 1;
#  $c += $count;
#}
#print "count:$c\n";

