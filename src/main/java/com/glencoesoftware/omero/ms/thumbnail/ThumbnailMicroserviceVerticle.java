/*
 * Copyright (C) 2017 Glencoe Software, Inc. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.glencoesoftware.omero.ms.thumbnail;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.IConnector;
import com.glencoesoftware.omero.ms.core.OmeroWebRedisSessionStore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import omero.model.Image;


/**
 * Main entry point for the OMERO thumbnail Vert.x microservice server.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailMicroserviceVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
        LoggerFactory.getLogger(ThumbnailVerticle.class);
  
    /**
     * Entry point method which starts the server event loop.
     * @param args Command line arguments.
     */
    @Override
    public void start(Future<Void> future) {
        log.info("Starting verticle");

        if (config().getBoolean("debug")) {
            Logger root = (Logger) LoggerFactory.getLogger(
                    "com.glencoesoftware.omero.ms");
            root.setLevel(Level.DEBUG);
        }

        // Deploy our dependency verticles
        JsonObject omero = config().getJsonObject("omero");
        vertx.deployVerticle(new ThumbnailVerticle(
                omero.getString("host"), omero.getInteger("port")),
                new DeploymentOptions().setWorker(true));

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Cookie handler so we can pick up the OMERO.web session
        router.route().handler(CookieHandler.create());

        // OMERO session handler which picks up the session key from the
        // OMERO.web session and joins it.
        JsonObject redis = config().getJsonObject("redis");
        final OmeroWebRedisSessionStore sessionStore =
                new OmeroWebRedisSessionStore(redis.getString("uri"));
        router.route().handler(event -> {
            Cookie cookie = event.getCookie("sessionid");
            if (cookie == null) {
                event.response().setStatusCode(403);
                event.response().end();
            }
            IConnector connector = sessionStore.getConnector(cookie.getValue());
            if (connector != null) {
                event.put("omero.session_key", connector.getOmeroSessionKey());
            }
            event.next();
        });

        // Thumbnail request handlers
        router.get(
                "/webclient/render_thumbnail/size/:longestSide/:imageId")
            .handler(this::renderThumbnail);

        int port = config().getInteger("port");
        log.info("Starting HTTP server *:{}", port);
        server.requestHandler(router::accept).listen(port, result -> {
            if (result.succeeded()) {
                future.complete();
            } else {
                future.fail(result.cause());
            }
        });
    }

    /**
     * Render thumbnail event handler. Responds with a <code>image/jpeg</code>
     * body on success based on the <code>longestSide</code> and
     * <code>imageId</code> encoded in the URL or HTTP 404 if the {@link Image}
     * does not exist or the user does not have permissions to access it.
     * @param event Current routing context.
     */
    private void renderThumbnail(RoutingContext event) {
        HttpServerRequest request = event.request();
        final String longestSide =
                request.getParam("longestSide") == null? "96"
                        : request.getParam("longestSide");
        final Long imageId = Long.parseLong(request.getParam("imageId"));
        final HttpServerResponse response = event.response();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("longestSide", Integer.parseInt(longestSide));
        data.put("imageId", imageId);
        data.put("omeroSessionKey", event.get("omero.session_key"));

        vertx.eventBus().send(
                "omero.render_thumbnail",
                Json.encode(data), result -> {
            try {
                if (result.failed()) {
                    Throwable t = result.cause();
                    int statusCode = 404;
                    if (t instanceof ReplyException) {
                        statusCode = ((ReplyException) t).failureCode();
                    }
                    response.setStatusCode(statusCode);
                    return;
                }
                byte[] thumbnail = (byte[]) result.result().body();
                response.headers().set("Content-Type", "image/jpeg");
                response.headers().set(
                        "Content-Length",
                        String.valueOf(thumbnail.length));
                response.write(Buffer.buffer(thumbnail));
            } finally {
                response.end();
                log.debug("Response ended");
            }
        });
    }

}
