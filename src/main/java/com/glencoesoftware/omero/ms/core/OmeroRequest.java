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

import java.io.Closeable;
import java.io.IOException;

import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.LoggerFactory;

import Glacier2.CannotCreateSessionException;
import Glacier2.PermissionDeniedException;
import omero.ServerError;


/**
 * OMERO session aware verticle which provides session joining and lifecycle
 * management.
 * @author Chris Allan <callan@glencoesoftware.com>
 * @param <T>
 *
 */
public class OmeroRequest<T> implements Closeable {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroRequest.class);

    private final String omeroSessionKey;

    private final omero.client client;

    public OmeroRequest(String host, int port, String omeroSessionKey)
            throws PermissionDeniedException, CannotCreateSessionException,
                ServerError {
        this.omeroSessionKey = omeroSessionKey;
        this.client = new omero.client(host, port);
        StopWatch t0 = new Slf4JStopWatch("joinSession");
        try {
            client.joinSession(omeroSessionKey).detachOnDestroy();
            log.debug("Successfully joined session: {}", omeroSessionKey);
        } finally {
            t0.stop();
        }
    }

    public T handler(OmeroRequestHandler<T> handler)
            throws ServerError {
            return handler.handle(client);
    }

    @Override
    public void close() throws IOException {
        StopWatch t0 = new Slf4JStopWatch("closeSession");
        try {
            client.closeSession();
            log.debug("Successfully closed session: {}", omeroSessionKey);
        } catch (Exception e) {
            log.error("Exception while closing session: {}", omeroSessionKey);
        } finally {
            t0.stop();
        }
    }

}
