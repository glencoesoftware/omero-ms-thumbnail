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

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.OmeroRequest;

import Glacier2.CannotCreateSessionException;
import Glacier2.PermissionDeniedException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * OMERO thumbnail provider verticle.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ThumbnailVerticle.class);

    /** OMERO server host */
    private final String host;

    /** OMERO server port */
    private final int port;

    /**
     * Default constructor.
     * @param host OMERO server host.
     * @param port OMERO server port.
     */
    public ThumbnailVerticle(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void start(Future<Void> future) {
        log.info("Starting verticle");

        vertx.eventBus().<String>consumer("omero.render_thumbnail", message -> {
            JsonObject data = new JsonObject(message.body());
            String omeroSessionKey = data.getString("omeroSessionKey");
            int longestSide = data.getInteger("longestSide");
            long imageId = data.getLong("imageId");

            try (OmeroRequest<byte[]> request = new OmeroRequest<byte[]>(
                     host, port, omeroSessionKey)) {
                byte[] thumbnail = request.handler(new ThumbnailRequestHandler(
                        longestSide, imageId)::renderThumbnail);
                message.reply(thumbnail);
            } catch (PermissionDeniedException
                     | CannotCreateSessionException e) {
                message.fail(403, "Permission denied");
            } catch (Exception e) {
                message.fail(500, "Exception while retrieving thumbnail");
            }
        });

        future.complete();
    }

}
