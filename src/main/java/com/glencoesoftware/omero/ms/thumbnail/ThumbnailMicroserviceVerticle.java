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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.OmeroWebRedisSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionRequestHandler;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionStore;

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

    /** OMERO.web session store */
    private OmeroWebSessionStore sessionStore;

    /**
     * Entry point method which starts the server event loop and initializes
     * our current OMERO.web session store.
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
                new DeploymentOptions().setWorker(true).setMultiThreaded(true));

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Cookie handler so we can pick up the OMERO.web session
        router.route().handler(CookieHandler.create());

        // OMERO session handler which picks up the session key from the
        // OMERO.web session and joins it.
        JsonObject redis = config().getJsonObject("redis");
        sessionStore = new OmeroWebRedisSessionStore(redis.getString("uri"));
        router.route().handler(
                new OmeroWebSessionRequestHandler(sessionStore));

        // Thumbnail request handlers
        router.get(
                "/webclient/render_thumbnail/size/:longestSide/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_thumbnail/:imageId/:longestSide*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_thumbnail/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/get_thumbnails/:longestSide*")
            .handler(this::getThumbnails);

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
     * Exit point method which when the verticle stops, cleans up our current
     * OMERO.web session store.
     */
    @Override
    public void stop() throws Exception {
        sessionStore.close();
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
                ThumbnailVerticle.RENDER_THUMBNAIL_EVENT,
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

    /**
     * Get thumbnails event handler. Responds with a JSON dictionary of Base64
     * encoded <code>image/jpeg</code> thumbnails keyed by {@link Image}
     * identifier. Each dictionary value is prefixed with
     * <code>data:image/jpeg;base64,</code> so that it can be used with
     * <a href="http://caniuse.com/#feat=datauri">data URIs</a>.
     * @param event Current routing context.
     */
    private void getThumbnails(RoutingContext event) {
        HttpServerRequest request = event.request();
        final String longestSide =
                request.getParam("longestSide") == null? "96"
                        : request.getParam("longestSide");
        final List<Long> imageIds = request.params().getAll("id").stream()
                .map(x -> Long.parseLong(x))
                .collect(Collectors.toList());
        final HttpServerResponse response = event.response();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("longestSide", Integer.parseInt(longestSide));
        data.put("imageIds", imageIds.toArray());
        data.put("omeroSessionKey", event.get("omero.session_key"));

        vertx.eventBus().send(
                ThumbnailVerticle.GET_THUMBNAILS_EVENT,
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
                String json = (String) result.result().body();
                response.headers().set("Content-Type", "application/json");
                response.headers().set(
                        "Content-Length",
                        String.valueOf(json.length()));
                response.write(json);
            } finally {
                response.end();
                log.debug("Response ended");
            }
        });
    }

}
