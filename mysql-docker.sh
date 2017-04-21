#!/bin/bash

# creates a new container, use start and stop with this name
docker run --name tracker-app-db -e MYSQL_ROOT_PASSWORD=rootpw --detach --publish 3306:3306 mariadb:latest

