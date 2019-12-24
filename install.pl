
### here is minimal utils to write dockerfile lines a bit more compact
### it should not be changed too often to avoid total rebuild

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
    sy('tar', 'xvf', $_), unlink $_ or die $_ for <*.tgz>, <*.tar.gz>, <*.tar.xz>;
    sy('unzip', $_), unlink $_ or die $_ for <*.zip>;
    sy('chown', '-R', 'c4:c4', '/download');
    -e $_ or mkdir $_ or die for "/tools";
    /^([a-z]+).*/ and rename $_,"/tools/$1" or die $_ for <*>;
} elsif($cmd eq 'useradd'){
    sy('useradd --home-dir /c4 --create-home --user-group --uid 1979 --shell /bin/bash c4');
} else {
    die;
}
