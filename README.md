[![Build Status](https://travis-ci.org/dkowis/tracker-app.svg?branch=master)](https://travis-ci.org/dkowis/tracker-app)

# Tracker application for slack

Goal is to have a robust tracker integration so we can manipulate tracker stories from slack!

Had to put this particular revision of it on hold. I can't run a Play Application (version 2.5.x) in
Cloud Foundry. See: https://github.com/cloudfoundry/java-buildpack-auto-reconfiguration/pull/58

Until that gets working, I'm going to use a different application framework that gives me a bit
more control over the bootup process: https://github.com/dkowis/scalatra-tracker-app

Once the play stuff gets fixed, perhaps I'll bring it back in. Play has a nice mature infrastructure
I just can't use it :(

## TODO
Plenty!
