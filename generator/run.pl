
use strict;
use Digest::MD5 qw(md5_hex);

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
#
my $generator_src_dir = ".";
my $generator_exec = "$generator_src_dir/target/universal/stage/bin/generator";
my @src_files = syf("find $generator_src_dir/src")=~/(\S+\.scala)\b/g;
@src_files || die;
my $sum = md5_hex(syf(join" ","cat",sort @src_files));
my $prev_sum_path = "$generator_src_dir/target/c4sum";
if(!-e $generator_exec or !-e $prev_sum_path or syf("cat $prev_sum_path") ne $sum){
    sy("sbt stage"); # in "."
    &$put_text($prev_sum_path,$sum);
}
print "generation starting\n";
sy("C4GENERATOR_VER=$sum $generator_exec");
print "generation finished\n";
