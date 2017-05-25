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

package com.glencoesoftware.omero.ms.core;

import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.MessageConsumer;

/**
 * OMERO session verticle which provides session joining and lifecycle
 * management.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class OmeroSessionVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroSessionVerticle.class);

    /** OMERO server host */
    private final String host;

    /** OMERO server port */
    private final int port;

    /**
     * Default constructor.
     * @param host OMERO server host.
     * @param port OMERO server port.
     */
    public OmeroSessionVerticle(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void start(Future<Void> future) {
        log.info("Starting verticle...");

        vertx.eventBus().<String>consumer("omero.join_session", message -> {
            String omeroSessionKey = message.body();
            log.debug("Attempting to join session: {}", omeroSessionKey);
            try {
                StopWatch t0 = new Slf4JStopWatch("joinSession");
                omero.client client;
                try {
                    if (omeroSessionKey == null) {
                        message.fail(403, "Missing session key");
                    }

                    client = new omero.client(host, port);
                    try {
                        client.joinSession(omeroSessionKey)
                            .detachOnDestroy();
                        log.debug(
                                "Successfully joined session: {}",
                                omeroSessionKey);
                        message.reply(client);
                    } catch (Exception e) {
                        log.debug(
                            "Unable to join session: {}", omeroSessionKey, e);
                        message.fail(403, "Unable to join session");
                    }
                } finally {
                    t0.stop();
                }
            } catch (Exception e) {
                log.error("Exception while joining session", e);
            }
        });

        future.complete();
    }

}
