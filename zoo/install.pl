
use strict;

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

my($cmd,@args)=@ARGV;

if($cmd eq 'apt'){
    $ENV{DEBIAN_FRONTEND} = 'noninteractive';
    sy('apt', 'update');
    sy('apt-get', 'install', '-y', @args);
    sy("rm -rf /var/lib/apt/lists/*");
} elsif($cmd eq 'curl'){
    for('/download'){ mkdir $_; chdir $_ or die $_ }
    sy('curl', '-LO', $_) for @args;
    sy('tar', 'xvf', $_), unlink $_ or die $_ for <*.tgz>, <*.tar.gz>;
    sy('unzip', $_), unlink $_ or die $_ for <*.zip>;
    sy('chown', '-R', 'c4:c4', '/download');
    /^([a-z]+).+/ and rename $_,"/c4/$1" or die $_ for <*>;
} else {
    die;
}
