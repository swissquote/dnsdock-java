[![Build Status](https://app.travis-ci.com/swissquote/dnsdock-java.svg?branch=master)](https://app.travis-ci.com/swissquote/dnsdock-java)

# DnsDock java

Dnsdock written in java with up to date docker client and improved container host names resolution

to run simply call: 

/usr/bin/docker run -v /var/run/docker.sock:/var/run/docker.sock --name dnsdock -p 172.17.0.1:53:53/udp dnsdock-java:1.0.0-SNAPSHOT
