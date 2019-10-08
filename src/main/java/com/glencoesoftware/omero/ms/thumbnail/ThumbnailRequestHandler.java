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

import static omero.rtypes.rint;
import static omero.rtypes.unwrap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.LoggerFactory;

import omero.ServerError;
import omero.api.ThumbnailStorePrx;
import omero.model.Image;
import omero.sys.EventContext;

import brave.ScopedSpan;
import brave.Tracing;

/**
 * OMERO session aware handler whose event handler method conforms to the
 * {@link OmeroRequestHandler} interface. This class is expected to be used as
 * a lambda handler.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailRequestHandler extends ThumbnailsRequestHandler {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ThumbnailRequestHandler.class);

    /**
     * {@link RenderingDef} identifier of the rendering settings to use when
     * requesting the thumbnail.
     */
    protected Optional<Long> renderingDefId;

    /**
     * Default constructor with the ability to specify a rendering definition
     * to use when requesting the thumbnail.
     * @param longestSide Size to confine or upscale the longest side of the
     * thumbnail to. The other side will then proportionately, based on aspect
     * ratio, be scaled accordingly.
     * @param imageId {@link Image} identifier to request a thumbnail for.
     * @param renderingDefId {@link RenderingDef} identifier of the rendering
     * settings to use.
     */
    public ThumbnailRequestHandler(
            int longestSide, long imageId, Optional<Long> renderingDefId) {
        super(longestSide, Arrays.asList(imageId));
        this.renderingDefId = renderingDefId;
    }

    /**
     * Default constructor.
     * @param longestSide Size to confine or upscale the longest side of the
     * thumbnail to. The other side will then proportionately, based on aspect
     * ratio, be scaled accordingly.
     * @param imageId {@link Image} identifier to request a thumbnail for.
     */
    public ThumbnailRequestHandler(int longestSide, long imageId) {
        super(longestSide, Arrays.asList(imageId));
    }

    /**
     * Retrieves a JPEG thumbnail from the server.
     * @return JPEG thumbnail byte array.
     */
    public byte[] renderThumbnail(omero.client client) {
        try {
            List<Image> images = getImages(client, imageIds);
            if (images.size() == 1) {
                return getThumbnail(
                        client, images.get(0), longestSide, renderingDefId);
            }
            log.debug("Cannot find any Image:{}", imageIds.get(0));
        } catch (Exception e) {
            log.error("Exception while retrieving thumbnails", e);
        }
        return null;
    }

    /**
     * Retrieves a JPEG thumbnail from the server.
     * @param client OMERO client to use for thumbnail retrieval.
     * @param image {@link Image} to retrieve thumbnail for.
     * @param longestSide Size to confine or upscale the longest side of each
     * thumbnail to. The other side will then proportionately, based on aspect
     * ratio, be scaled accordingly.
     * @param renderingDefId {@link RenderingDef} identifier of the rendering
     * settings to use. May be <code>null</code>.
     * @return JPEG thumbnail byte array.
     * @throws ServerError If there was any sort of error retrieving the
     * thumbnail.
     */
    protected byte[] getThumbnail(
            omero.client client, Image image, int longestSide,
            Optional<Long> renderingDefId)
                    throws ServerError{
        ThumbnailStorePrx thumbnailStore =
                client.getSession().createThumbnailStore();
        try {
            Map<String, String> ctx = new HashMap<String, String>();
            long pixelsId = (Long) unwrap(image.getPrimaryPixels().getId());
            // Assume all the groups are the same
            ctx.put(
                "omero.group",
                String.valueOf(unwrap(
                        image.getDetails().getGroup().getId()))
            );

            boolean hasRenderingSettings =
                    setPixelsId(ctx, thumbnailStore, pixelsId);
            if (renderingDefId.isPresent()) {
                ScopedSpan span =
                        Tracing.currentTracer().startScopedSpan("set_rendering_def_id");
                try {
                    thumbnailStore.setRenderingDefId(renderingDefId.get(), ctx);
                } finally {
                    span.finish();
                }
            }
            if (!hasRenderingSettings) {
                // Operate as the object owner if we are an administrator
                EventContext eventContext =
                        client.getSession().getAdminService().getEventContext();
                if (eventContext.memberOfGroups.contains(0L)) {
                    ctx.put(
                        "omero.user",
                        String.valueOf(unwrap(
                                image.getDetails().getOwner().getId()))
                    );
                }
                ScopedSpan span =
                        Tracing.currentTracer().startScopedSpan("reset_defaults");
                try {
                    thumbnailStore.resetDefaults();
                } finally {
                    span.finish();
                }
                setPixelsId(ctx, thumbnailStore, pixelsId);
            }
            ScopedSpan span =
                    Tracing.currentTracer().startScopedSpan("get_thumbnail_by_longest_side");
            try {
                return thumbnailStore.getThumbnailByLongestSide(
                        rint(longestSide), ctx);
            } finally {
                span.finish();
            }
        } finally {
            thumbnailStore.close();
        }
    }

    /**
     * Sets the {@link Pixels} identifier on a thumbnail store in an
     * instrumented fashion.
     * @param ctx Calling context.
     * @param thumbnailStore Thumbnail store to set <code>pixelsId</code> on.
     * @param pixelsId {@link Pixels} identifier to set.
     * @return <code>true</code> if a set of rendering settings is available
     * for the {@link Pixels} object identified by <code>pixelsId</code>
     * otherwise <code>false</code>
     * @throws ServerError If there was any sort of error setting the
     * identifier.
     */
    private boolean setPixelsId(
            Map<String, String> ctx, ThumbnailStorePrx thumbnailStore,
            long pixelsId) throws ServerError {
        ScopedSpan span =
                Tracing.currentTracer().startScopedSpan("set_pixels_id");
        try {
            return thumbnailStore.setPixelsId(pixelsId, ctx);
        } finally {
            span.finish();
        }
    }
}
