
use strict;

my $zoo_port = 2181;
my $zoo_host = "zookeeper";
my $bootstrap_server = "broker:9092";
my $bin = "kafka/bin";

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };

my @tasks;
push @tasks, [zookeeper=>sub{
    &$put_text("/c4/zookeeper.properties",join "\n",
        "dataDir=db4/zookeeper",
        "clientPort=$zoo_port"
    );
    &$exec("$bin/zookeeper-server-start.sh", "zookeeper.properties");
}];
push @tasks, [broker=>sub{
    &$put_text("/c4/server.properties",join "\n",
        "listeners=PLAINTEXT://$bootstrap_server",
        "log.dirs=db4/kafka-logs",
        "zookeeper.connect=$zoo_host:$zoo_port",
        "message.max.bytes=250000000" #seems to be compressed
    );
    &$exec("$bin/kafka-server-start.sh", "server.properties");
}];
push @tasks, [frpc=>sub{
    &$exec("frp/frpc", "-c", "/c4deploy/frpc.ini");
}];
push @tasks, [gate=>sub{
    &$exec("sh", "gate.sh");
}];
push @tasks, [desktop=>sub{
    my $pass_fn = $ENV{C4AUTH_KEY_FILE} || die;
    my $pass = `cat $pass_fn`=~/(\S+)/ ? $1 : die;
    my $pass_line = qq[Option "SpicePassword" "$pass"\n];
    my $conf_fn = "c4spiceqxl.xorg.conf";
    my $conf_cont = join '', map{!/"SpicePassword"/?$_:$pass_line}
        `cat /etc/X11/$conf_fn`;
    &$put_text("/etc/X11/$conf_fn", $conf_cont);
    #print $conf_cont;
    #$ENV{XSPICE_PASSWORD} = $pass;
    &$exec("Xspice","--config",$conf_fn,"--xsession","openbox",":1"); #,"--vdagent"
}];
push @tasks, [def=>sub{
    m{([^/]+)$} and (-e $1 or symlink $_,$1) or die for </c4deploy/*>;
    &$exec("sh", "serve.sh");
}];

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;
