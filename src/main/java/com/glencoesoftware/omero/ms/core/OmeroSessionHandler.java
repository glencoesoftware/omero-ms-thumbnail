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

import io.vertx.core.Handler;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;

/**
 * OMERO session handler which ensures that a valid session is available for
 * all other handlers via the <code>omero.client</code> key on the current
 * {@link RoutingContext}. The OMERO session key will also be available via
 * the <code>omero.session_key</code> key. Should be added to
 * {@link io.vertx.ext.web.Router} <em>before</em> all other handlers which
 * utilize the OMERO session.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class OmeroSessionHandler implements Handler<RoutingContext> {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroSessionHandler.class);

    /** OMERO server host */
    private final String host;

    /** OMERO server port */
    private final int port;

    /** OMERO.web session store. */
    private final OmeroWebSessionStore sessionStore;

    /**
     * Default constructor.
     * @param host OMERO server host.
     * @param port OMERO server port.
     * @param sessionStore OMERO.web session store.
     */
    public OmeroSessionHandler(
            String host, int port, OmeroWebSessionStore sessionStore) {
        this.host = host;
        this.port = port;
        this.sessionStore = sessionStore;
    }

    @Override
    public void handle(RoutingContext event) {
        try {
            Cookie cookie = event.getCookie("sessionid");
            if (cookie == null) {
                event.response().setStatusCode(403);
                event.response().end();
                return;
            }
            IConnector connector = sessionStore.getConnector(cookie.getValue());

            StopWatch t0 = new Slf4JStopWatch("joinSession");
            omero.client client;
            try {
                if (connector == null) {
                    event.response().setStatusCode(403);
                    event.response().end();
                    return;
                }

                String omeroSessionKey = connector.getOmeroSessionKey();
                event.put("omero.session_key", omeroSessionKey);
                client = new omero.client(host, port);
                client.joinSession(omeroSessionKey).detachOnDestroy();
                log.debug("Successfully joined session: {}", omeroSessionKey);
                event.put("omero.client", client);
                event.next();
            } finally {
                t0.stop();
            }
        } catch (Exception e) {
            log.error("Exception while joining session", e);
        }
    }

}
