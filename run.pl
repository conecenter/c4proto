
### here is script to run different containers in runtime/production where most of sources do not present

use strict;

my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my @tasks;

my $serve = sub{
    sy("python3","-u","vault.py");
    sy("perl","ceph.pl");
    $ENV{JAVA_TOOL_OPTIONS} = join " ", $ENV{JAVA_TOOL_OPTIONS},
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+UnlockDiagnosticVMOptions", "-XX:GCLockerRetryAllocationCount=32",
        "-XX:MaxGCPauseMillis=200", "-XX:GCTimeRatio=1", "-XX:MinHeapFreeRatio=15", "-XX:MaxHeapFreeRatio=50",
        "-XX:+UseStringDeduplication";
    # https://www.javacodegeeks.com/2017/11/minimize-java-memory-usage-right-garbage-collector.html
    # with G1/ZGC unused RAM is released back to OS
    # G1 gets many GCLocker oom errors on ubuntu 20.04, so we tried ZGC, but it was too hungry, then GCLockerRetryAllocationCount was found
    local $ENV{C4PUBLIC_PATH} = "htdocs";
    local $ENV{CLASSPATH} = "app/*"; # join ":", sort <app/*.jar>;
    &$exec("sh", "serve.sh");
};

push @tasks, [main=>sub{&$serve()}];

my($cmd,@args)=@ARGV;
$cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
