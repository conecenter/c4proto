
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }

my @tasks;

my $serve = sub{
    sy("python3","vault.py");
    my $ceph_auth_path = $ENV{C4CEPH_AUTH};
    if($ceph_auth_path eq "/tmp/ceph.auth"){
        my $conf_dir = $ENV{C4S3_CONF_DIR}||die;
        my $content = join "&", map{"$$_[0]=$$_[1]"}
            (map{[$$_[0]=>syf("cat $conf_dir/$$_[1]")]} [url=>"address"],[id=>"key"],[pass=>"secret"]),
            [bucket=>$ENV{C4INBOX_TOPIC_PREFIX}||die];
        open FF,">",$ceph_auth_path and print FF $content and close FF or die "put_text($!)($ceph_auth_path)";
    }
    $ENV{JAVA_TOOL_OPTIONS} = join " ", $ENV{JAVA_TOOL_OPTIONS},
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+UnlockDiagnosticVMOptions", "-XX:GCLockerRetryAllocationCount=8",
        "-XX:MaxGCPauseMillis=200", "-XX:GCTimeRatio=1", "-XX:MinHeapFreeRatio=15", "-XX:MaxHeapFreeRatio=50",
        "-XX:+UseStringDeduplication";
    # https://www.javacodegeeks.com/2017/11/minimize-java-memory-usage-right-garbage-collector.html
    # with G1/ZGC unused RAM is released back to OS
    # G1 gets many GCLocker oom errors on ubuntu 20.04, so we tried ZGC, but it was too hungry, then GCLockerRetryAllocationCount was found
    local $ENV{C4PUBLIC_PATH} = "htdocs";
    local $ENV{CLASSPATH} = join ":", sort <app/*.jar>;
    &$exec("sh", "serve.sh");
};

push @tasks, [main=>sub{&$serve()}];

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
