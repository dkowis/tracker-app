[![Build Status](https://travis-ci.org/dkowis/scalatra-tracker-app.svg?branch=master)](https://travis-ci.org/dkowis/scalatra-tracker-app)
# Tracker App #

A bit of slack integration with Pivotal Tracker.

## Current Features

### Story Unfurling
When a channel is registered with a pivotal tracker project, tracker-bot will unfurl the stories when they're mentioned
by #storyId or by full story url. It will ignore story IDs or links that aren't part of the project, even if it has access
to them.

You can also quick create a started chore. It creates the chore, assigns it to you, and starts it. This is a quick way
to handle the "Could you just...?" questions that show up in slack while tracking them easily. It might not stick around.

## Build & Run ##

TODO!

## Work in progress!
Converting to Akka HTTP. It's actors under the hood, so it makes sense to make it actors all the way down.

