[![Build Status](https://travis-ci.org/dkowis/tracker-app.svg?branch=master)](https://travis-ci.org/dkowis/tracker-app)
# Tracker App #

A bit of slack integration with Pivotal Tracker.

## Current Features

### Registration of a channel with multiple tracker projects
You can register a channel with one or more tracker projects. Any tracker story ID that the bot has access to
will be unfurled, if the story is within the projects registered.

### Story Unfurling
When a channel is registered with a pivotal tracker project, tracker-bot will unfurl the stories when they're mentioned
by #storyId or by full story url. It will ignore story IDs or links that aren't part of the project, even if it has access
to them.


## Build & Run ##

TODO!

## Work in progress!
Converting to Akka HTTP. It's actors under the hood, so it makes sense to make it actors all the way down.
Akka HTTP now supports HTTPS proxies, so I should be able to make this conversion


## Development database setup

```mysql

CREATE USER 'tracker-app'@'%' IDENTIFIED BY 'a strong password';

-- Use this type of database from starting, but we'll have to use different migrations
CREATE DATABASE `tracker-app` CHARACTER SET utf8mb4;

-- this one should be okay
CREATE DATABASE `tracker-app` CHARACTER SET utf8;

GRANT ALL PRIVILEGES ON `tracker-app`.* TO 'tracker-app'@'%';

```

### TODO list

* [ ] Redo the database, maybe use postgresql to solve the utf8mb4 
* [ ] Much prettier project listing
* [ ] Do something with iteration details? (although we don't use that)

### This really doesn't fit the project

But it'd be stuff that would be really nice to have because it's our current workflow.
* [ ] fork the project and include some github details to match our teams workflow?
    * [ ] Milestones, issues, pull requests
    * [ ] It can unfurl pull requests, or the associated stories?
    * [ ] Alert when a milestone is past due?
    * [ ] Alert when a milestone is about to be due?
    * [ ] Annoy about pull requests that have been open too long