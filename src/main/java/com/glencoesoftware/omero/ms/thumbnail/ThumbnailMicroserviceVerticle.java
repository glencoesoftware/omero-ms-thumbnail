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

import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.OmeroWebJDBCSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebRedisSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionRequestHandler;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionStore;

import io.vertx.config.ConfigStoreOptions;
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
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
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

    static {
        com.glencoesoftware.omero.ms.core.SSLUtils.fixDisabledAlgorithms();
    }

    /**
     * Entry point method which starts the server event loop and initializes
     * our current OMERO.web session store.
     */
    @Override
    public void start(Future<Void> future) {
        log.info("Starting verticle");

        ConfigStoreOptions store = new ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(new JsonObject()
                        .put("path", "conf/config.yaml")
                )
                .setOptional(true);
        ConfigRetriever retriever = ConfigRetriever.create(
                vertx, new ConfigRetrieverOptions()
                        .setIncludeDefaultStores(true)
                        .addStore(store));
        retriever.getConfig(ar -> {
            try {
                deploy(ar.result(), future);
            } catch (Exception e) {
                future.fail(e);
            }
        });
    }

     /**
      * Deploys our verticles and performs general setup that depends on
      * configuration.
     * @param config Current configuration
     */
    public void deploy(JsonObject config, Future<Void> future) {
        log.info("Deploying verticle");

        // Deploy our dependency verticles
        JsonObject omero = config.getJsonObject("omero");
        if (omero == null) {
            throw new IllegalArgumentException(
                    "'omero' block missing from configuration");
        }
        vertx.deployVerticle(new ThumbnailVerticle(
                omero.getString("host"), omero.getInteger("port")),
                new DeploymentOptions()
                        .setWorker(true)
                        .setMultiThreaded(true)
                        .setConfig(config));

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // Cookie handler so we can pick up the OMERO.web session
        router.route().handler(CookieHandler.create());

        // OMERO session handler which picks up the session key from the
        // OMERO.web session and joins it.
        JsonObject sessionStoreConfig = config.getJsonObject("session-store");
        if (sessionStoreConfig == null) {
            throw new IllegalArgumentException(
                    "'session-store' block missing from configuration");
        }
        String sessionStoreType = sessionStoreConfig.getString("type");
        String sessionStoreUri = sessionStoreConfig.getString("uri");
        if (sessionStoreType.equals("redis")) {
            sessionStore = new OmeroWebRedisSessionStore(sessionStoreUri);
        } else if (sessionStoreType.equals("postgres")) {
            sessionStore = new OmeroWebJDBCSessionStore(
                sessionStoreUri,
                vertx);
        } else {
            throw new IllegalArgumentException(
                "Missing/invalid value for 'session-store.type' in config");
        }

        // Get Thumbnail Microservice Information
        router.options().handler(this::getMicroserviceDetails);

        router.route().handler(
                new OmeroWebSessionRequestHandler(config, sessionStore, vertx));

        // Thumbnail request handlers
        router.get(
                "/webclient/render_thumbnail/size/:longestSide/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webclient/render_thumbnail/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_thumbnail/:imageId/:longestSide*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_thumbnail/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webclient/render_birds_eye_view/:imageId/:longestSide*")
            .handler(this::renderThumbnail);
        router.get(
                "/webclient/render_birds_eye_view/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_birds_eye_view/:imageId/:longestSide*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/render_birds_eye_view/:imageId*")
            .handler(this::renderThumbnail);
        router.get(
                "/webgateway/get_thumbnails/:longestSide*")
            .handler(this::getThumbnails);
        router.get(
                "/webgateway/get_thumbnails*")
            .handler(this::getThumbnails);
        router.get(
                "/webclient/get_thumbnails/:longestSide*")
            .handler(this::getThumbnails);
        router.get(
                "/webclient/get_thumbnails*")
            .handler(this::getThumbnails);

        int port = config.getInteger("port");
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
     * Get information about microservice.
     * Confirms that this is a microservice
     * @param event Current routing context.
     */
    private void getMicroserviceDetails(RoutingContext event) {
        log.info("Getting Microservice Details");
        String version = Optional.ofNullable(
            this.getClass().getPackage().getImplementationVersion())
            .orElse("development");
        JsonObject resData = new JsonObject()
                        .put("provider", "ThumbnailMicroservice")
                        .put("version", version)
                        .put("features", new JsonArray());
        event.response()
            .putHeader("content-type", "application-json")
            .end(resData.encodePrettily());
    }

    /**
     * Render thumbnail event handler. Responds with a <code>image/jpeg</code>
     * body on success based on the <code>longestSide</code> and
     * <code>imageId</code> encoded in the URL or HTTP 404 if the {@link Image}
     * does not exist or the user does not have permissions to access it.
     * @param event Current routing context.
     */
    private void renderThumbnail(RoutingContext event) {
        final HttpServerRequest request = event.request();
        final HttpServerResponse response = event.response();
        final Map<String, Object> data = new HashMap<String, Object>();
        data.put("longestSide",
                Optional.ofNullable(request.getParam("longestSide"))
                    .map(Integer::parseInt)
                    .orElse(96));
        data.put("imageId", Long.parseLong(request.getParam("imageId")));
        data.put("omeroSessionKey", event.get("omero.session_key"));
        data.put("renderingDefId",
                Optional.ofNullable(request.getParam("rdefId"))
                    .map(Long::parseLong)
                    .orElse(null));

        vertx.eventBus().<byte[]>send(
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
                byte[] thumbnail = result.result().body();
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
        final HttpServerRequest request = event.request();
        final HttpServerResponse response = event.response();
        final Map<String, Object> data = new HashMap<String, Object>();
        final String callback = request.getParam("callback");
        data.put("longestSide",
                Optional.ofNullable(request.getParam("longestSide"))
                    .map(Integer::parseInt)
                    .orElse(96));
        data.put("imageIds",
                request.params().getAll("id").stream()
                    .map(Long::parseLong)
                    .collect(Collectors.toList())
                    .toArray());
        data.put("omeroSessionKey", event.get("omero.session_key"));

        vertx.eventBus().<String>send(
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
                String json = result.result().body();
                String contentType = "application/json";
                if (callback != null) {
                    json = String.format("%s(%s);", callback, json);
                    contentType = "application/javascript";
                }
                response.headers().set("Content-Type", contentType);
                response.headers().set(
                        "Content-Length", String.valueOf(json.length()));
                response.write(json);
            } finally {
                response.end();
                log.debug("Response ended");
            }
        });
    }

}
