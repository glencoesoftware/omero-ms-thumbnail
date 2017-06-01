[![AppVeyor status](https://ci.appveyor.com/api/projects/status/github/omero-ms-thumbnail)](https://ci.appveyor.com/project/gs-jenkins/omero-ms-thumbnail)

OMERO Thumbnail Microservice
============================

OMERO thumbnail Vert.x asynchronous microservice server endpoint for OMERO.web.

Requirements
============

* OMERO 5.2.x+
* OMERO.web 5.2.x+
* Redis backed sessions
* Java 8+

Workflow
========

The microservice server endpoint for OMERO.web relies on the following
workflow::

1. Setup of OMERO.web to use database or Redis backed sessions

1. Running the microservice endpoint for OMERO.web

1. Redirecting your OMERO.web installation to use the microservice endpoint

Development Installation
========================

1. Clone the repository::

        git clone git@github.com:glencoesoftware/omero-ms-thumbnail.git

1. Run the Gradle build and utilize the artifacts as required::

        ./gradlew installDist
        cd build/install
        ...

1. Log in to the OMERO.web instance you would like to develop against with
your web browser and with the developer tools find the `sessionid` cookie
value. This is the OMERO.web session key.

1. Run single or multiple thumbnail tests using `curl`::

        curl -H 'Cookie: sessionid=<omero_web_session_key>' \
            http://localhost:8080/render_thumbnail/size/96/<image_id>

Eclipse Configuration
=====================

1. Run the Gradle Eclipse task::

        ./gradlew eclipse

1. Configure your environment::

        cp conf.json.example conf.json

1. Add a new Run Configuration with a main class of `io.vertx.core.Starter`::

        run "com.glencoesoftware.omero.ms.thumbnail.ThumbnailMicroserviceVerticle" \
            -conf "conf.json"

Build Artifacts
===============

The latest artifacts, built by AppVeyor, can be found here::

* https://ci.appveyor.com/project/gs-jenkins/omero-ms-thumbnail/build/artifacts

Configuring and Running the Server
==================================

The thumbnail microservice server endpoint piggybacks on the OMERO.web Django
session.  As such it is essential that as a prerequisite to running the
server that your OMERO.web instance be configured to use Redis backed sessions.
Filesystem backed sessions **are not** supported.

1. Configure the application::

        cp conf.json.example path/to/conf.json

1. Start the server::

        omero-ms-thumbnail -conf path/to/conf.json

Configuring Logging
-------------------

Logging is provided using the logback library. You can configure logging by
copying the included `logback.xml.example`, editing as required, and then
specifying the configuration when starting the microservice server::

    cp logback.xml.example logback.xml
    ...
    JAVA_OPTS="-Dlogback.configurationFile=/path/to/logback.xml" \
        omero-ms-thumbnail ...

Debugging the logback configuration can be done by providing the additional
`-Dlogback.debug=true` property.

Redirecting OMERO.web to the Server
===================================

What follows is a snippet which can be placed in your nginx configuration,
**before** your default OMERO.web location handler, to redirect both
*webclient* and *webgateway* thumbnail rendering currently used by OMERO.web
to the thumbnail microservice server endpoint::

    upstream thumbnail-backend {
        server 127.0.0.1:8080 fail_timeout=0 max_fails=0;
    }

    ...

    location /webgateway/render_thumbnail/ {
        proxy_pass http://thumbnail_backend;
    }

    location /webclient/render_thumbnail/ {
        proxy_pass http://thumbnail-backend;
    }

Running Tests
=============

Using Gradle run the unit tests:

    ./gradlew test

Reference
=========

* https://github.com/glencoesoftware/omero-ms-core
* https://lettuce.io/
* http://vertx.io/
