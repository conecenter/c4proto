
use strict;
use Digest::MD5 qw(md5_hex);
use JSON::XS;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }
sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $if_changed = sub{
    my($path,$will,$then)=@_;
    return if (-e $path) && syf("cat $path") eq $will;
    &$then();
    &$put_text($path,$will);
};
#
my $reconfigure = sub{
    my($json,$dir,$ver)=@_;
    my @classpath = syf("coursier fetch org.scalameta:scalameta_2.13:4.2.3")=~/(\S+)/g;
    my $class_dir = "$dir/.bloop/generator/v$ver/classes";
    my $conf = {
        "version" => "1.0.0",
        "project" => {
            "name" => "generator",
            "directory" => "$dir",
            "sources" => ["$dir/src/main/scala"],
            "dependencies" => [],
            "classpath" => [@classpath],
            "out" => "$dir/.bloop/generator",
            "classesDir" => $class_dir,
            "scala" => {
                "organization" => "org.scala-lang",
                "name" => "scala-compiler",
                "version" => "2.13.0",
                "options" => [],
                "jars" => [grep{m"/scala-(library|compiler|reflect)-[^/]*\.jar$"}@classpath],
            },
#            "scala" => {
#                "jars" => [...jline-2.14.6.jar"],
#                "setup" => {
#                    "order" => "mixed",
#                    "addLibraryToBootClasspath" => "true",
#                    "addCompilerToClasspath" => "false",
#                    "addExtraJarsToClasspath" => "false",
#                    "manageBootClasspath" => "true",
#                    "filterLibraryFromClasspath" => "true"
#                }
#            },
            # resources: [".../src/main/resources"]
        }
    };
    &$put_text("$dir/.bloop/generator.json",$json->encode($conf));
};
my $get_src_sum = sub{
    my($dir,$ver)=@_;
    my @src_files = syf("find $dir/src")=~/(\S+\.scala)\b/g;
    @src_files || die;
    return md5_hex("${ver}v\n".syf(join" ","cat",sort @src_files));
};

my $generator_src_dir = syf("pwd")=~/^(\S+)\s*$/ ? $1 : die;
my $ver = "013";
my $main = $ENV{C4GENERATOR_MAIN} || die;
#use Time::HiRes;print Time::HiRes::time,"\n";
my $json = JSON::XS->new->ascii(1)->canonical(1)->pretty(1);
mkdir "$generator_src_dir/.bloop/";
&$if_changed("$generator_src_dir/.bloop/c4ver",$ver,sub{ &$reconfigure($json,$generator_src_dir,$ver) });
my $sum = &$get_src_sum($generator_src_dir,$ver);
&$if_changed("$generator_src_dir/.bloop/c4sum",$sum,sub{ sy("cd $generator_src_dir && bloop compile generator") });
print "generation starting\n";
my $project = $json->decode(syf("cat $generator_src_dir/.bloop/generator.json"))->{project}||die;
my $classpath = join ":", @{$$project{classpath}||die},($$project{classesDir}||die);
sy("cd $generator_src_dir && C4GENERATOR_VER=$sum java -classpath $classpath $main");
print "generation finished\n";
