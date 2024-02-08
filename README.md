[![AppVeyor status](https://ci.appveyor.com/api/projects/status/github/omero-ms-thumbnail)](https://ci.appveyor.com/project/gs-jenkins/omero-ms-thumbnail)

OMERO Thumbnail Microservice
============================

OMERO thumbnail Vert.x asynchronous microservice server endpoint for OMERO.web.

Requirements
============

* OMERO 5.6.x+
* OMERO.web 5.6.x+
* Redis backed sessions
* Java 8+

Workflow
========

The microservice server endpoint for OMERO.web relies on the following
workflow::

1. Setup of OMERO.web to use Redis backed sessions

1. Configuring the microservice endpoint

1. Running the microservice endpoint for OMERO.web

1. Redirecting your OMERO.web installation to use the microservice endpoint

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

1. Configure the application by editing `conf/config.yaml`

1. Start the server

        omero-ms-thumbnail

Configuring Logging
-------------------

Logging is provided using the logback library.  You can configure logging by
copying the included `logback.xml.example`, editing as required, and then
specifying the configuration when starting the microservice server::

    cp logback.xml.example logback.xml
    ...
    JAVA_OPTS="-Dlogback.configurationFile=/path/to/logback.xml" \
        omero-ms-thumbnail ...

Debugging the logback configuration can be done by providing the additional
`-Dlogback.debug=true` property.

Using systemd
-------------

If you are using `systemd` you can place an appropriately modified version of
the included `omero-ms-thumbnail.service` into your `/etc/systemd/system`
directory and then execute::

    systemctl daemon-reload
    systemctl start omero-ms-thumbnail.service

Running `systemctl status omero-ms-thumbnail.service` will then produce
output similar to the following::

    # systemctl status omero-ms-thumbnail.service
    ● omero-ms-thumbnail.service - OMERO thumbnail microservice server
       Loaded: loaded (/etc/systemd/system/omero-ms-thumbnail.service; disabled; vendor preset: disabled)
       Active: active (running) since Thu 2017-06-01 14:40:53 UTC; 8min ago
     Main PID: 9096 (java)
       CGroup: /system.slice/omero-ms-thumbnail.service
               └─9096 java -Dlogback.configurationFile=/opt/omero/omero-ms-thumbnail-0.1.0-SNAPSHOT/logback.xml -classpath /opt/omero/omero-ms-thumbnail-0.1.0-SNAPSHOT/lib/omero-ms-thumbnail-0.1.0-SNAPSHOT.jar:/opt/omero/omero-...

    Jun 01 14:40:53 demo.glencoesoftware.com systemd[1]: Started OMERO thumbnail microservice server.
    Jun 01 14:40:53 demo.glencoesoftware.com systemd[1]: Starting OMERO thumbnail microservice server...
    Jun 01 14:40:54 demo.glencoesoftware.com omero-ms-thumbnail[9096]: Jun 01, 2017 2:40:54 PM io.vertx.core.spi.resolver.ResolverProvider
    Jun 01 14:40:54 demo.glencoesoftware.com omero-ms-thumbnail[9096]: INFO: Using the default address resolver as the dns resolver could not be loaded
    Jun 01 14:40:55 demo.glencoesoftware.com omero-ms-thumbnail[9096]: Jun 01, 2017 2:40:55 PM io.vertx.core.Starter
    Jun 01 14:40:55 demo.glencoesoftware.com omero-ms-thumbnail[9096]: INFO: Succeeded in deploying verticle

Redirecting OMERO.web to the Server
===================================

What follows is a snippet which can be placed in your nginx configuration,
**before** your default OMERO.web location handler, to redirect both
*webclient* and *webgateway* thumbnail and birds-eye view rendering
currently used by OMERO.web to the thumbnail microservice server endpoint::

    upstream thumbnail_backend {
        server 127.0.0.1:8080 fail_timeout=0 max_fails=0;
    }

    ...

    location ~ ^/(webgateway|webclient)/(render_thumbnail|render_birds_eye_view|get_thumbnails)/ {
        proxy_pass http://thumbnail_backend;
    }


Development Installation
========================

1. Clone the repository::

        git clone https://github.com/glencoesoftware/omero-ms-thumbnail.git

1. Run the Gradle build and utilize the artifacts as required::

        ./gradlew installDist
        cd build/install
        ...

1. Log in to the OMERO.web instance you would like to develop against with
your web browser and with the developer tools find the `sessionid` cookie
value. This is the OMERO.web session key.

1. Run single or multiple thumbnail tests using `curl`::

        curl -H 'Cookie: sessionid=<omero_web_session_key>' \
            http://localhost:8080/webclient/render_thumbnail/size/96/<image_id>

Eclipse Configuration
=====================

1. Run the Gradle Eclipse task::

        ./gradlew eclipse

1. Configure your environment::

        mkdir conf
        cp src/dist/conf/config.yaml conf/
        # Edit as appropriate

1. Add a new Run Configuration with a main class of `io.vertx.core.Starter`::

        run "com.glencoesoftware.omero.ms.thumbnail.ThumbnailMicroserviceVerticle"

Running Tests
=============

Using Gradle run the unit tests:

    ./gradlew test

Reference
=========

* https://github.com/glencoesoftware/omero-ms-core
* https://lettuce.io/
* http://vertx.io/
