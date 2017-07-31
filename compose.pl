use strict;
use YAML::XS qw(LoadFile DumpFile Dump);

$YAML::XS::QuoteNumericStrings = 1;
#$YAML::Syck::SortKeys = 1;
#$YAML::Syck::SingleQuote = 1;

use Digest::MD5 qw(md5_hex);

my $registry_prefix = "localhost:5000/c4-";
my $inbox_prefix = '';
my $bin = "kafka/bin";

my $bootstrap_server = "broker:9092";
my $c_script = "inbox_configure.pl";
my $user = "c4";


sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $merge; $merge = sub{
    my($b,$o)=@_;
    "HASH" eq ref $b and "HASH" eq ref $o or return $o;
    my $res = {%$b,%$o};
    +{map{($_=>(exists $$b{$_})?&$merge($$b{$_},$$res{$_}):$$res{$_})} keys %$res};
};

my $extract_env = sub{
    my %opt = @_;
    my %env = map{/^C4/?($_=>$opt{$_}):()} keys %opt;
    (
        (%env ? (environment => \%env):()),
        (map{/^C4/?():($_=>$opt{$_})} keys %opt)
    )
};

my $app_user = sub{
    my %opt = @_;
    (user=>$user, working_dir=>"/$user");
};

my $volumes = sub{(volumes => [map{"vol-$user-$_:/$user/$_"}@_])};

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
            command => ["perl",$c_script],
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
    volumes => { map{("vol-c4-$_"=>{})} qw[db4] },
    version => "3.2",
}};

#...expose

my $gen_ip = sub{
    join ".", 127, map{hex} md5_hex($_[0])=~/(..)(..)(..)/ ? ($1,$2,$3) : die
};

my $build = sub{
    my($location,$override_arg,$range)=@_;
    my $override = &$merge(&$template_yml(),$override_arg);
    my $override_services = $$override{services} || {};
    my $generated_services = {map{
        my $service_name = $_;
        my $service = $$override_services{$service_name} || die;
        my $img = $$service{C4APP_IMAGE} || die;
        ($service_name => {&$extract_env(
            restart=>"unless-stopped",
            ($$service{C4STATE_TOPIC_PREFIX}?(
                &$app_user(),
                depends_on => ["broker"],
                C4BOOTSTRAP_SERVERS => $bootstrap_server,
                C4INBOX_TOPIC_PREFIX => $inbox_prefix,
                &$volumes("db4"),
            ):()),
            %$service,
            image => "$registry_prefix$location-$img",
            ((-e "c4deploy/$img/Dockerfile")?(build => "c4deploy/$img"):()),
            ($$service{expose} ? (ports=>[map{(
                ($range ? {published=>$range+$_, target=>$_} : ()),
                #{published=>&$gen_ip("$location-$service_name").":$_", target=>$_},
                ($_>60 ? &$gen_ip("$location-$service_name").":$_:$_" : ()),
                ### YAML will parse numbers in the format xx:yy as sexagesimal (base 60). For this reason, we recommend always explicitly specifying your port mappings as strings.
            )}@{$$service{expose}||die}]):())
        )});
    } keys %$override_services };
    my $generated = { %$override, services => $generated_services };

    DumpFile("docker-compose.yml",$generated);
    #my $text = Dump($generated);
    #$text=~s/(\n\s+-\s+)([^\n]*\S:\d\d)/$1"$2"/gs;
    #&$put_text("docker-compose.yml",$text);
    sy("cp docker-compose.yml c4deploy/docker-compose.yml.dump");
    sy("docker-compose -p $location build");
};

# pass src commit
# fix prod yml
# try prod
# >2 >4
#extra_hosts:
# - "somehost:162.242.195.82"



    #skh test_ui
    #skh frs
    #frs frs

my $su = sub{
    my($host_dir)=@_;
    print "docker run --rm "
     ." --userns=host "
     ." -v /var/run/docker.sock:/var/run/docker.sock "
     ." -v $host_dir:/c4deploy "
};

my @tasks = (
    ["su", $su],
    ["up", sub{
        my($location,$config,$range)=@_;
        my $override = LoadFile("c4deploy/docker-compose.$config.yml");
        &$build($location,$override,$range);
        sy("docker-compose -p $location up -d --remove-orphans");
    }],
    ["push", sub{
        my($location)=@_;
        &$build($location,{});
        sy("docker-compose -p $location push");
        sy("docker push $registry_prefix$location-composer");
    }],
);

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
