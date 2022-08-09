
use strict;
sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    print "put_text ($fn)\n";
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my ($mod) = @ARGV;
#open FF,'|-','sbt',"$mod/compile"; close FF or die; ### perl during 'system' will not fail on ^C; so we use 'open'
my $tmp = "target/c4";
my $sbt = qq^cd $tmp/mod.$mod.d && JAVA_TOOL_OPTIONS="\$JAVA_TOOL_OPTIONS \$C4BUILD_JAVA_TOOL_OPTIONS" sbt^;
sy("$sbt compile");
my $cp = syf("$sbt 'export main/runtime:fullClasspath'")=~m{(/\S+)\s*$} ? "$1" : die;
use JSON::XS;
my $json = JSON::XS->new->ascii(1)->canonical(1)->pretty(1);
my $res = {
    %{$json->decode(syf("cat $tmp/paths.json"))||die},
    C4MODULES => ($json->decode(syf("cat $tmp/build.json"))->{modules_by_root_mod}{$mod}||die),
    CLASSPATH => $cp,
};
my $sh = join "", map{"export $_=$$res{$_}\n"} sort keys %$res;
&$put_text("$tmp/mod.$mod.classpath.json", $json->encode($res));
&$put_text("$tmp/mod.$mod.classpath.sh", $sh);
