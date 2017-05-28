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

Eclipse Configuration
=====================

1. Run the Gradle Eclipse task::

        ./gradlew eclipse

1. Configure your environment::

        cp conf.json.example conf.json

1. Add a new Run Configuration with a main class of `io.vertx.core.Starter::

        run "com.glencoesoftware.omero.ms.thumbnail.ThumbnailMicroserviceVerticle" \
            -conf "conf.json"

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

Redirecting OMERO.web to the Server
===================================

What follows are two snippets which can be placed in your nginx configuration
for OMERO.web to redirect searches to the search server endpoint::

    upstream thumbnail-backend {
        server 127.0.0.1:8080 fail_timeout=0 max_fails=0;
    }

    ...

    location /webclient/render_thumbnail/
        proxy_pass http://thumbnail-backend/;
    }

Running Tests
=============

Using Gradle run the unit tests:

    ./gradlew test

Reference
=========

* https://lettuce.io/
* http://vertx.io/
