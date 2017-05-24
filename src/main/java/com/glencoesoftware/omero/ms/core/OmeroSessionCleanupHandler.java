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

import org.slf4j.LoggerFactory;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * OMERO session cleanup handler which ensures that <code>closeSession()</code>
 * is called on the current session. Should be added to
 * {@link io.vertx.ext.web.Router} <em>after</em> all other handlers which
 * utilize the OMERO session.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class OmeroSessionCleanupHandler implements Handler<RoutingContext> {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroSessionCleanupHandler.class);

    @Override
    public void handle(RoutingContext event) {
        String omeroSessionKey = event.get("omero.session_key");
        omero.client client = event.get("omero.client");
        event.vertx().executeBlocking(future -> {
            try {
                client.closeSession();
                future.complete(true);
            } catch (Exception e) {
                future.complete(false);
                log.error("Exception while closing session", e);
            }
        }, result -> {
            Boolean success = (Boolean) result.result();
            if (success) {
                log.debug("Successfully closed session: {}", omeroSessionKey);
            } else {
                log.error("Failed to close session: {}", omeroSessionKey);
            }
        });
    }

}
