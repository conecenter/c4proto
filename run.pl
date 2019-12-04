
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

my $bootstrap_port = 9092;
my $zoo_port = 2181;
my $zoo_host = "127.0.0.1";
my $http_port = 8067;
my $sse_port = 8068;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ print "$_\n" and return scalar `$_` for @_ }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

my @tasks;
push @tasks, [zookeeper=>sub{
    my $data_dir = $ENV{C4DATA_DIR} || die;
    &$put_text("/c4/zookeeper.properties",join "\n",
        "dataDir=$data_dir/zookeeper",
        "clientPort=$zoo_port",
        "clientPortAddress=$zoo_host",
    );
    &$exec("zookeeper-server-start.sh", "zookeeper.properties");
}];
push @tasks, [broker=>sub{
    my $data_dir = $ENV{C4DATA_DIR} || die;
    my $ext_host = $ENV{C4BOOTSTRAP_EXT_HOST} || die;
    my $ext_port = $ENV{C4BOOTSTRAP_EXT_PORT} || die;
    &$put_text("/c4/server.properties", join '', map{"$_\n"}
        "log.dirs=$data_dir/kafka-logs",
        "zookeeper.connect=$zoo_host:$zoo_port",
        "message.max.bytes=250000000", #seems to be compressed
        "socket.request.max.bytes=250000000",
        "listeners=INTERNAL://:$bootstrap_port,EXTERNAL://:$ext_port", #0.0.0.0
        "advertised.listeners=INTERNAL://127.0.0.1:$bootstrap_port,EXTERNAL://$ext_host:$ext_port",
        "listener.security.protocol.map=INTERNAL:SSL,EXTERNAL:SSL",
        "inter.broker.listener.name=EXTERNAL",
    );
    my $props = $ENV{C4SSL_PROPS} || die;
    sy("cat $props >> server.properties");
    &$exec("kafka-server-start.sh", "server.properties");
}];
push @tasks, [haproxy=>sub{
    $ENV{C4HTTP_PORT} = $http_port;
    $ENV{C4SSE_PORT} = $sse_port;
    &$exec("perl", "haproxy.pl");
}];
my $serve = sub{
    $ENV{CLASSPATH} = join ":", sort <app/*.jar>;
    &$exec("sh", "serve.sh");
};
push @tasks, [gate=>sub{
    $ENV{C4HTTP_PORT} = $http_port;
    $ENV{C4SSE_PORT} = $sse_port;
    $ENV{C4BOOTSTRAP_SERVERS} = "127.0.0.1:$bootstrap_port";
    $ENV{JAVA_TOOL_OPTIONS} = join " ", $ENV{JAVA_TOOL_OPTIONS},
        "-XX:+UseG1GC","-XX:MaxGCPauseMillis=200","-XX:+ExitOnOutOfMemoryError",
        "-XX:GCTimeRatio=1","-XX:MinHeapFreeRatio=15","-XX:MaxHeapFreeRatio=50";
    &$serve();
}];
push @tasks, [main=>sub{
    m{([^/]+)$} and (-e $1 or symlink $_,$1) or die for </c4conf/*>;
    &$serve();
}];

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
