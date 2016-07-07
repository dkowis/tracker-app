# Scalatra Tracker App #

Porting over the tracker app from scala Play! 2.5.x to scalatra. The play framework support in cloud foundry needs some help before I can actually use it.

Waiting on https://github.com/cloudfoundry/java-buildpack-auto-reconfiguration/pull/58

Most of the magic happens in the actor system behind the scenes. There's not really any web pages worth of stuff yet.

## Build & Run ##

```sh
$ cd Scalatra_Tracker_App
$ ./sbt
> jetty:start
> browse
```

If `browse` doesn't launch your browser, manually open [http://localhost:8080/](http://localhost:8080/) in your browser.
