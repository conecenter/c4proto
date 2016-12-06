#!/usr/bin/perl

use strict;

sub sy{ system @_ and die $?,@_ }

(-e $_ or mkdir $_) and chdir $_ or die for "tmp";
sy("wget http://www-eu.apache.org/dist/kafka/0.10.1.0/kafka_2.11-0.10.1.0.tgz");
sy("tar -xzf kafka_2.11-0.10.1.0.tgz")