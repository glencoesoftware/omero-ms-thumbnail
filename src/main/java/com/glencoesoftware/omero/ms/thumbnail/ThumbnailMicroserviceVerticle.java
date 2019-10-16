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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.glencoesoftware.omero.ms.core.LogSpanReporter;
import com.glencoesoftware.omero.ms.core.OmeroHttpTracingHandler;
import com.glencoesoftware.omero.ms.core.OmeroVerticleFactory;
import com.glencoesoftware.omero.ms.core.OmeroWebJDBCSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebRedisSessionStore;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionRequestHandler;
import com.glencoesoftware.omero.ms.core.OmeroWebSessionStore;
import com.glencoesoftware.omero.ms.core.PrometheusSpanHandler;

import brave.ScopedSpan;
import brave.Tracing;
import brave.http.HttpTracing;
import brave.sampler.Sampler;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
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
import io.prometheus.client.vertx.MetricsHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import omero.model.Image;
import zipkin2.Span;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.okhttp3.OkHttpSender;


/**
 * Main entry point for the OMERO thumbnail Vert.x microservice server.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailMicroserviceVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
        LoggerFactory.getLogger(ThumbnailMicroserviceVerticle.class);

    /** OMERO.web session store */
    private OmeroWebSessionStore sessionStore;

    /** OMERO server Spring application context. */
    private ApplicationContext context;

    /** VerticleFactory */
    private OmeroVerticleFactory verticleFactory;

    /** Default number of workers to be assigned to the worker verticle */
    private int DEFAULT_WORKER_POOL_SIZE;

    /** Zipkin HTTP Tracing*/
    private HttpTracing httpTracing;

    private OkHttpSender sender;

    private AsyncReporter<Span> spanReporter;

    private Tracing tracing;

    static {
        com.glencoesoftware.omero.ms.core.SSLUtils.fixDisabledAlgorithms();
    }

    /**
     * Entry point method which starts the server event loop and initializes
     * our current OMERO.web session store.
     */
    @Override
    public void start(Promise<Void> promise) {
        log.info("Starting verticle");

        DEFAULT_WORKER_POOL_SIZE =
                Runtime.getRuntime().availableProcessors() * 2;

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
                deploy(ar.result(), promise);
            } catch (Exception e) {
                promise.fail(e);
            }
        });
    }

     /**
      * Deploys our verticles and performs general setup that depends on
      * configuration.
     * @param config Current configuration
     */
    public void deploy(JsonObject config, Promise<Void> promise) {

        context = new ClassPathXmlApplicationContext(
                "classpath:ome/config.xml",
                "classpath:ome/services/datalayer.xml",
                "classpath*:beanRefContext.xml");

        JsonObject omero = config.getJsonObject("omero");
        if (omero == null) {
            throw new IllegalArgumentException(
                    "'omero' block missing from configuration");
        }

        JsonObject httpTracingConfig =
                config.getJsonObject("http-tracing", new JsonObject());
        Boolean tracingEnabled =
                httpTracingConfig.getBoolean("enabled", false);
        if (tracingEnabled) {
            String zipkinUrl = httpTracingConfig.getString("zipkin-url");
            log.info("Tracing enabled: {}", zipkinUrl);
            sender = OkHttpSender.create(zipkinUrl);
            spanReporter = AsyncReporter.create(sender);
            PrometheusSpanHandler prometheusSpanHandler = new PrometheusSpanHandler();
            tracing = Tracing.newBuilder()
                .sampler(Sampler.ALWAYS_SAMPLE)
                .localServiceName("omero-ms-thumbnail")
                .addFinishedSpanHandler(prometheusSpanHandler)
                .spanReporter(spanReporter)
                .build();
        } else {
            log.info("Tracing disabled");
            PrometheusSpanHandler prometheusSpanHandler = new PrometheusSpanHandler();
            spanReporter = new LogSpanReporter();
            tracing = Tracing.newBuilder()
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .localServiceName("omero-ms-thumbnail")
                    .addFinishedSpanHandler(prometheusSpanHandler)
                    .spanReporter(spanReporter)
                    .build();
        }

        httpTracing = HttpTracing.newBuilder(tracing).build();
        log.info("Deploying verticle");

        // Deploy our dependency verticles
        verticleFactory = (OmeroVerticleFactory)
                context.getBean("omero-ms-verticlefactory");
        vertx.registerVerticleFactory(verticleFactory);

        int workerPoolSize = Optional.ofNullable(
                config.getInteger("worker_pool_size")
                ).orElse(DEFAULT_WORKER_POOL_SIZE);

        vertx.deployVerticle("omero:omero-ms-thumbnail-verticle",
                new DeploymentOptions()
                        .setWorker(true)
                        .setInstances(workerPoolSize)
                        .setWorkerPoolName("thumbnail-pool")
                        .setWorkerPoolSize(workerPoolSize)
                        .setConfig(config));

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        router.get("/metrics")
        .order(-2)
        .handler(new MetricsHandler());

        List<String> tags = new ArrayList<String>();
        tags.add("omero.session_key");

        Handler<RoutingContext> routingContextHandler =
                new OmeroHttpTracingHandler(httpTracing, tags);
        // Set up HttpTracing Routing
        router.route()
            .order(-1) // applies before other routes
            .handler(routingContextHandler)
        .failureHandler(routingContextHandler);

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
                new OmeroWebSessionRequestHandler(config, sessionStore));

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
        server.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                promise.complete();
            } else {
                promise.fail(result.cause());
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
        tracing.close();
        if (spanReporter != null) {
            spanReporter.close();
        }
        if (sender != null) {
            sender.close();
        }
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
        ScopedSpan span = Tracing.currentTracer().startScopedSpan("ms_render_thumbnail");
        final HttpServerRequest request = event.request();
        final HttpServerResponse response = event.response();
        MultiMap params = request.params();
        ThumbnailCtx thumbnailCtx = new ThumbnailCtx(params,
            event.get("omero.session_key"));
        thumbnailCtx.injectCurrentTraceContext();
        vertx.eventBus().<byte[]>request(
                ThumbnailVerticle.RENDER_THUMBNAIL_EVENT,
                Json.encode(thumbnailCtx), result -> {
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
                span.finish();
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
        ScopedSpan span = Tracing.currentTracer().startScopedSpan("ms_get_thumbnails");
        final HttpServerRequest request = event.request();
        final HttpServerResponse response = event.response();
        final String callback = request.getParam("callback");
        MultiMap params = request.params();
        ThumbnailCtx thumbnailCtx = new ThumbnailCtx(params,
            event.get("omero.session_key"));
        thumbnailCtx.injectCurrentTraceContext();

        vertx.eventBus().<String>request(
                ThumbnailVerticle.GET_THUMBNAILS_EVENT,
                Json.encode(thumbnailCtx), result -> {
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
                span.finish();
                log.debug("Response ended");
            }
        });
    }

}
