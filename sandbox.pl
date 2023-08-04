
use strict;
use JSON::XS;
use POSIX ":sys_wait_h";

sub so{ print join(" ",@_),"\n"; system @_; }
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ my $res = scalar `$_[0]`; print "$_[0]\n$res"; $res }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $exec_at = sub{
    my($dir,$env,@args)=@_;
    chdir $dir or die $dir;
    $ENV{$_} = $$env{$_}, print "$_='$$env{$_}' \\\n" for keys %$env;
    &$exec(@args);
};
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text_or_empty = sub{
    my($path)=@_;
    -e $path or return "";
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};
my $repeat = sub{
    my ($f,@state) = @_;
    @state = &$f(@state) while @state > 0;
};
my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

my $debug_port = 5005;
my $serve_proxy = sub{
    my $debug_ext_address = "0.0.0.0:".&$mandatory_of(C4DEBUG_PORT => \%ENV);
    my $debug_int_address = &$get_text_or_empty("/c4/haproxy.to");
    $debug_ext_address && $debug_int_address or &$exec("sleep","infinity");
    my $ha_cfg_path = "/c4/haproxy.cfg";
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
    &$exec("haproxy","-f",$ha_cfg_path);
};

###

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

my $get_debug_ip = sub{
    my($pid)=@_;
    "127.1.".(($pid>>8) & 0xFF).".".($pid & 0xFF);
};

my $remake = sub{
    my($build_dir,$droll) = @_;
    my $arg = &$get_text_or_empty("/c4/debug-tag") || die;
    my $tmp = "$build_dir/target/c4";
    my $build_data = JSON::XS->new->decode(&$get_text_or_empty("$tmp/build.json"));
    my ($nm,$mod,$cl) = map{$$build_data{tag_info}{$arg}{$_}||die} qw[name mod main];
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR => \%ENV);
    my $user = $ENV{HOSTNAME}=~/^de-(\w+)-/ ? $1 : die;
    local $ENV{KUBECONFIG} = $ENV{C4KUBECONFIG};
    so("python3", "-u", "$proto_dir/build_remote.py", "compile",
        "--proj-tag", $arg, "--user", $user, "--context", $build_dir,
    ) and return ();
    so("perl", "$proto_dir/prod.pl", "build_client_changed", $build_dir, "dev") and return ();
    #
    my $ppid = $$;
    my $pid = fork();
    defined $pid or die;
    if($pid == 0){
        my $dir = "$droll$$";
        &$prep_empty_dir($dir);
        my $debug_int_ip = &$get_debug_ip($$);
        my $paths = JSON::XS->new->decode(syf("python3 $proto_dir/build_env.py $build_dir $mod"));
        my $tool_opt = "-XX:+UseG1GC -XX:GCTimeRatio=1 -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=50 -XX:+UseStringDeduplication $ENV{JAVA_TOOL_OPTIONS}"; #-XX:NativeMemoryTracking=summary
        ### if need heap >32G keeping 32bit pointers, insert: -XX:ObjectAlignmentInBytes=16 -Xmx45g
        my $env = {
            %$paths,
            (-e "/c4/debug-components") ? (C4DEBUG_COMPONENTS => "1") : (),
            JAVA_TOOL_OPTIONS => $tool_opt,
            (-e "/c4/debug-enable") ? (C4JDWP_ADDRESS => "$debug_int_ip:$debug_port") : (),
            C4PARENT_PID => $ppid,
            C4READINESS_PATH => "$dir/c4is-ready",
            C4STATE_TOPIC_PREFIX => $nm,
            C4APP_CLASS => "ee.cone.c4actor.ParentElectorClientApp",
            C4APP_CLASS_INNER => $cl,
        };
        &$exec_at($dir,$env,"java","ee.cone.c4actor.ServerMain");
        die;
    }
    print &$colored_line(bright_yellow=>"Spawned $pid");
    #
    my $debug_int_ip = &$get_debug_ip($pid);
    &$put_text("/c4/haproxy.to","$debug_int_ip:$debug_port");
    sy("cd /c4 && supervisorctl restart proxy");
    #
    return ($pid);
};

my $loop_iteration = sub{
    my ($was_active_pid_list) = @_;
    my $build_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $droll = "$build_dir/target/dev-rolling-";
    my @active_pid = grep{
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
    } @$was_active_pid_list;
    print "active pid list: @active_pid\n" if @active_pid > 1;
    my $last_ready = (grep{ -e "$droll$_/c4is-ready" } @active_pid)[-1];
    my $curr_ver = &$get_text_or_empty("$build_dir/target/gen-ver");
    my $was_ver = &$get_text_or_empty("$build_dir/target/gen-ver-started");
    &$put_text("$build_dir/target/gen-ver-started",$curr_ver);
    #
    @active_pid = (@active_pid, $was_ver eq $curr_ver ? () : &$remake($build_dir,$droll));
    my @to_kill = grep{ $last_ready ne $_ } @active_pid[0..@active_pid-2];
    if(@to_kill){
        print &$colored_line(bright_yellow=>"Killing: ".join(", ",@to_kill));
        kill 'TERM', @to_kill;
    }
    sleep 1;
    [@active_pid];
};


my $serve_loop = sub{
#    &$repeat(sub{
#        my @st = @_;
#        so("b l o o p about") or return ();
#        sleep 1;
#        @st
#    },1);
    &$repeat($loop_iteration,[]);
};
#? say Failed

####

my $serve_history = sub{
    &$put_text("/c4/.bashrc", join "\n",
        &$get_text_or_empty("/c4/.bashrc"), #syf("ssh-agent"),
        "export KUBECONFIG=$ENV{C4KUBECONFIG}", # $C4KUBECONFIG was empty at this stage
        "export KUBE_EDITOR=mcedit",
        'history -c && history -r /c4/.bash_history_get && export PROMPT_COMMAND="history -a /c4/.bash_history_put"',
        qq[alias prod="perl $ENV{C4CI_PROTO_DIR}/prod.pl "],
        'alias h="history|grep "',
    );
    #
    my $env = {
        CLASSPATH => (syf("coursier fetch --classpath org.apache.kafka:kafka-clients:2.8.0")=~/(\S+)/ ? $1 : die),
        C4HISTORY_PUT => "/c4/.bash_history_put",
        C4HISTORY_GET => "/c4/.bash_history_get",
    };
    &$exec_at($ENV{C4CI_PROTO_DIR},$env,"java","--source","15","history.java");
};

my $init = sub{
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR => \%ENV);
    sy("python3","-u","$proto_dir/vault.py");
    sy("perl","$proto_dir/ceph.pl");
    #
    my $sock = "/c4/supervisor.sock";
    &$put_text("/c4/supervisord.conf", join '', map{"$_\n"}
        "[supervisord]",
        "nodaemon=true",
        "logfile=/dev/null",
        "logfile_maxbytes=0",
        "[unix_http_server]",
        "file=$sock",
        "[supervisorctl]",
        "serverurl=unix://$sock",
        "[rpcinterface:supervisor]",
        "supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface",
        (map{(
            "[program:$_]",
            "command=perl $ENV{C4CI_PROTO_DIR}/sandbox.pl $_",
            "autorestart=true",
            "stopasgroup=true",
            "killasgroup=true",
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
        )} qw[proxy loop history]) #b l o o p
    );
    &$exec("supervisord","-c","/c4/supervisord.conf")
};
my $cmd_map = {
    proxy => $serve_proxy,
    loop => $serve_loop,
    history => $serve_history,
    main => $init,
};
$| = 1;
$$cmd_map{$ARGV[0]}->();