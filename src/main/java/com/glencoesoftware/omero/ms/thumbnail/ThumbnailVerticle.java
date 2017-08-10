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
import java.util.Map.Entry;

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.OmeroRequest;

import Glacier2.CannotCreateSessionException;
import Glacier2.PermissionDeniedException;
import IceUtilInternal.Base64;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import omero.model.Image;

/**
 * OMERO thumbnail provider worker verticle. This verticle is designed to be
 * deployed in worker mode and in either a single or multi threaded mode. It
 * acts as a pool of workers to handle blocking thumbnail rendering events
 * dispatched via the Vert.x EventBus.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailVerticle extends AbstractVerticle {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ThumbnailVerticle.class);

    public static final String RENDER_THUMBNAIL_EVENT =
            "omero.render_thumbnail";

    public static final String GET_THUMBNAILS_EVENT =
            "omero.get_thumbnails";

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

    /* (non-Javadoc)
     * @see io.vertx.core.AbstractVerticle#start()
     */
    @Override
    public void start() {
        log.info("Starting verticle");

        vertx.eventBus().<String>consumer(
                RENDER_THUMBNAIL_EVENT, this::renderThumbnail);
        vertx.eventBus().<String>consumer(
                GET_THUMBNAILS_EVENT, this::getThumbnails);
    }

    /**
     * Render thumbnail event handler. Responds with a <code>image/jpeg</code>
     * body on success or a failure.
     * @param message JSON encoded event data. Required keys are
     * <code>omeroSessionKey</code> (String), <code>longestSide</code>
     * (Integer), and <code>imageId</code> (Long).
     */
    private void renderThumbnail(Message<String> message) {
        JsonObject data = new JsonObject(message.body());
        String omeroSessionKey = data.getString("omeroSessionKey");
        int longestSide = data.getInteger("longestSide");
        long imageId = data.getLong("imageId");
        Optional<Long> renderingDefId =
                Optional.ofNullable(data.getLong("renderingDefId"));
        log.debug(
            "Render thumbnail request Image:{} longest side {} RenderingDef:{}",
            imageId, longestSide, renderingDefId.orElse(null));

        try (OmeroRequest<byte[]> request = new OmeroRequest<byte[]>(
                 host, port, omeroSessionKey)) {
            byte[] thumbnail = request.execute(new ThumbnailRequestHandler(
                    longestSide, imageId, renderingDefId)::renderThumbnail);
            if (thumbnail == null) {
                message.fail(404, "Cannot find Image:" + imageId);
            } else {
                message.reply(thumbnail);
            }
        } catch (PermissionDeniedException
                 | CannotCreateSessionException e) {
            String v = "Permission denied";
            log.debug(v);
            message.fail(403, v);
        } catch (Exception e) {
            String v = "Exception while retrieving thumbnail";
            log.error(v, e);
            message.fail(500, v);
        }
    }

    /**
     * Get thumbnails event handler. Responds with a JSON dictionary of Base64
     * encoded <code>image/jpeg</code> thumbnails keyed by {@link Image}
     * identifier. Each dictionary value is prefixed with
     * <code>data:image/jpeg;base64,</code> so that it can be used with
     * <a href="http://caniuse.com/#feat=datauri">data URIs</a>.
     * @param message JSON encoded event data. Required keys are
     * <code>omeroSessionKey</code> (String), <code>longestSide</code>
     * (Integer), and <code>imageIds</code> (List<Long>).
     */
    private void getThumbnails(Message<String> message) {
        JsonObject data = new JsonObject(message.body());
        String omeroSessionKey = data.getString("omeroSessionKey");
        int longestSide = data.getInteger("longestSide");
        JsonArray imageIdsJson = data.getJsonArray("imageIds");
        List<Long> imageIds = new ArrayList<Long>();
        for (int i = 0; i < imageIdsJson.size(); i++) {
            imageIds.add(imageIdsJson.getLong(i));
        }
        log.debug(
            "Render thumbnail request ImageIds:{} longest side {}",
            imageIds, longestSide);

        try (OmeroRequest<Map<Long, byte[]>> request =
                new OmeroRequest<Map<Long, byte[]>>(
                        host, port, omeroSessionKey)) {
            Map<Long, byte[]> thumbnails = request.execute(
                    new ThumbnailsRequestHandler(
                            longestSide, imageIds)::renderThumbnails);

            if (thumbnails == null) {
                message.fail(404, "Cannot find one or more Images");
            } else {
                Map<Long, String> thumbnailsJson = new HashMap<Long, String>();
                for (Entry<Long, byte[]> v : thumbnails.entrySet()) {
                    thumbnailsJson.put(
                        v.getKey(),
                        "data:image/jpeg;base64," + Base64.encode(v.getValue())
                    );
                }
                message.reply(Json.encode(thumbnailsJson));
            }
        } catch (PermissionDeniedException
                 | CannotCreateSessionException e) {
            String v = "Permission denied";
            log.debug(v);
            message.fail(403, v);
        } catch (Exception e) {
            String v = "Exception while retrieving thumbnail";
            log.error(v, e);
            message.fail(500, v);
        }
    }

}
