
use strict;
use JSON::XS;
use POSIX ":sys_wait_h";

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
my $mandatory_of = sub{ my($k,$h)=@_; (exists $$h{$k}) ? $$h{$k} : die "no $k" };

###

my $supervisor = sub{ sy("supervisorctl","-c","/c4/supervisord.conf",@_) };

my $get_tag = sub{ &$get_text_or_empty("/c4/debug-tag") || die };

my $serve_app = sub{
    my $build_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR => \%ENV);
    my $build_data = JSON::XS->new->decode(&$get_text_or_empty("$build_dir/target/c4/build.json"));
    my ($nm,$mod,$cl) = map{$$build_data{tag_info}{&$get_tag()}{$_}||die} qw[name mod main];
    my $paths = JSON::XS->new->decode(syf("python3 $proto_dir/build_env.py $build_dir $mod"));
    my $ready_path = "/c4/c4is-ready";
    !-e $_ or unlink $_ or die for $ready_path;
    my $tool_opt = join(" ", qw[
        -XX:+UseG1GC -XX:GCTimeRatio=1 -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=50 -XX:+UseStringDeduplication
        -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=32 -XX:+DebugNonSafepoints
    ], $ENV{JAVA_TOOL_OPTIONS});
    #-XX:NativeMemoryTracking=summary
    ### if need heap >32G keeping 32bit pointers, insert: -XX:ObjectAlignmentInBytes=16 -Xmx45g
    my $env = {
        KUBECONFIG => &$mandatory_of(C4KUBECONFIG => \%ENV),
        %$paths,
        (-e "/c4/debug-components") ? (C4DEBUG_COMPONENTS => "1") : (),
        JAVA_TOOL_OPTIONS => $tool_opt,
        (-e "/c4/debug-enable") ? (C4JDWP_ADDRESS => "0.0.0.0:".&$mandatory_of(C4DEBUG_PORT => \%ENV)) : (),
        C4READINESS_PATH => $ready_path,
        C4STATE_TOPIC_PREFIX => $nm,
        C4APP_CLASS => "ee.cone.c4actor.ParentElectorClientApp",
        C4APP_CLASS_INNER => $cl,
    };
    &$exec_at("/c4",$env,"java","ee.cone.c4actor.ServerMain");
};

my $serve_build = sub{
    my ($was_active_pid_list) = @_;
    my $build_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR => \%ENV);
    my $user = $ENV{HOSTNAME}=~/^de-(\w+)-/ ? $1 : die;
    local $ENV{KUBECONFIG} = $ENV{C4KUBECONFIG};
    my @opt = ("--proj-tag", &$get_tag(), "--user", $user, "--context", $build_dir);
    sy("python3", "-u", "$proto_dir/build_remote.py", "compile", @opt);
    sy("perl", "$proto_dir/prod.pl", "build_client", $build_dir, "dev");
    &$supervisor("restart","app");
};

my $serve_prebuild = sub{
    my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR => \%ENV);
    my $build_dir = &$mandatory_of(C4CI_BUILD_DIR => \%ENV);
    sy("python3", "-u", "$proto_dir/build.py", $build_dir);
    &$supervisor("restart","build");
};

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
        JAVA_TOOL_OPTIONS => "",
        CLASSPATH => (syf("coursier fetch --classpath org.apache.kafka:kafka-clients:3.7.1")=~/(\S+)/ ? $1 : die),
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
            "[program:$$_[0]]",
            "command=perl $ENV{C4CI_PROTO_DIR}/sandbox.pl $$_[0]",
            "autostart=".($$_[1]?"true":"false"),
            "autorestart=".($$_[2]?"true":"false"),
            "stopasgroup=true",
            "killasgroup=true",
            "stderr_logfile=/dev/stderr",
            "stderr_logfile_maxbytes=0",
            "stdout_logfile=/dev/stdout",
            "stdout_logfile_maxbytes=0",
        )} [prebuild=>1,0], [build=>0,0], [app=>0,0], [history=>1,1])
    );
    &$exec("supervisord","-c","/c4/supervisord.conf")
};
my $cmd_map = {
    prebuild => $serve_prebuild, build => $serve_build, app => $serve_app, history => $serve_history, main => $init
};
$| = 1;
$$cmd_map{$ARGV[0]}->();