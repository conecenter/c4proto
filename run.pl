
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

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

my @tasks;

my $serve = sub{
    my $ceph_auth_path = $ENV{C4CEPH_AUTH};
    $ceph_auth_path eq "/tmp/ceph.auth" and &$put_text($ceph_auth_path, join "&", map{"$$_[0]=$$_[1]"}
        (map{[$$_[0]=>&$get_text(($ENV{C4S3_CONF_DIR}||die)."/$$_[1]")]} [url=>"address"],[id=>"key"],[pass=>"secret"]),
        [bucket=>$ENV{C4INBOX_TOPIC_PREFIX}||die]
    );
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
