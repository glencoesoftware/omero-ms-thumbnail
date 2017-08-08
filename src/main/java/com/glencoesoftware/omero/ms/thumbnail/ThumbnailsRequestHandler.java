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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.LoggerFactory;

import omero.ServerError;
import omero.api.ThumbnailStorePrx;
import omero.model.IObject;
import omero.model.Image;
import omero.sys.ParametersI;

/**
 * OMERO session aware handler whose event handler method conforms to the
 * {@link OmeroRequestHandler} interface. This class is expected to be used as
 * a lambda handler.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailsRequestHandler {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ThumbnailsRequestHandler.class);

    /** Longest side of the thumbnail. */
    protected final int longestSide;

    /** Image identifiers to request thumbnails for. */
    protected final List<Long> imageIds;

    /**
     * Default constructor.
     * @param longestSide Size to confine or upscale the longest side of the
     * thumbnail to. The other side will then proportionately, based on aspect
     * ratio, be scaled accordingly.
     * @param imageIds {@link Image} identifiers to request thumbnails for.
     */
    public ThumbnailsRequestHandler(int longestSide, List<Long> imageIds) {
        this.longestSide = longestSide;
        this.imageIds = imageIds;
    }

    /**
     * Retrieves a map of JPEG thumbnails from the server.
     * @return Map of {@link Image} identifier to JPEG thumbnail byte array.
     */
    public Map<Long, byte[]> renderThumbnails(omero.client client) {
        try {
            List<Image> images = getImages(client, imageIds);
            if (images.size() != 0) {
                return getThumbnails(client, images, longestSide);
            } else {
                log.debug("Cannot find any Images with Ids {}", imageIds);
            }
        } catch (Exception e) {
            log.error("Exception while retrieving thumbnails", e);
        }
        return null;
    }

    /**
     * Retrieves a list of loaded {@link Image}s from the server.
     * @param client OMERO client to use for querying.
     * @param imageIds {@link Image} identifiers to query for.
     * @return List of loaded {@link Image} and primary {@link Pixels}.
     * @throws ServerError If there was any sort of error retrieving the images.
     */
    protected List<Image> getImages(omero.client client, List<Long> imageIds)
            throws ServerError {
        Map<String, String> ctx = new HashMap<String, String>();
        ctx.put("omero.group", "-1");
        ParametersI params = new ParametersI();
        params.addIds(imageIds);
        StopWatch t0 = new Slf4JStopWatch("getImages");
        try {
            return client.getSession().getQueryService().findAllByQuery(
                "SELECT i FROM Image as i " +
                "JOIN FETCH i.pixels as p WHERE i.id IN (:ids)",
                params, ctx
            ).stream().map(x -> (Image) x).collect(Collectors.toList());
        } finally {
            t0.stop();
        }
    }

    /**
     * Retrieves a map of JPEG thumbnails from the server.
     * @param client OMERO client to use for thumbnail retrieval.
     * @param images {@link Image} list to retrieve thumbnails for.
     * @param longestSide Size to confine or upscale the longest side of each
     * thumbnail to. The other side will then proportionately, based on aspect
     * ratio, be scaled accordingly.
     * @return Map of {@link Image} identifier to JPEG thumbnail byte array.
     * @throws ServerError If there was any sort of error retrieving the
     * thumbnails.
     */
    protected Map<Long, byte[]> getThumbnails(
            omero.client client, List<Image> images, int longestSide)
                    throws ServerError{
        ThumbnailStorePrx thumbnailStore =
                client.getSession().createThumbnailStore();
        try {
            Map<String, String> ctx = new HashMap<String, String>();
            Map<Long, Long> pixelsIdImageIds = new HashMap<Long, Long>();
            for (IObject o : images) {
                Image image = (Image) o;
                pixelsIdImageIds.put(
                    (Long) unwrap(image.getPrimaryPixels().getId()),
                    (Long) unwrap(image.getId())
                );
                // Assume all the groups are the same
                ctx.put(
                    "omero.group",
                    String.valueOf(unwrap(
                            image.getDetails().getGroup().getId()))
                );
            }
            StopWatch t0 = new Slf4JStopWatch("getThumbnailByLongestSideSet");
            try {
                Map<Long, byte[]> pixelsIdThumbnails =
                        thumbnailStore.getThumbnailByLongestSideSet(
                            rint(longestSide),
                            new ArrayList<Long>(pixelsIdImageIds.keySet()),
                            ctx
                        );
                Map<Long, byte[]> imageIdThumbnails =
                        new HashMap<Long, byte[]>();
                for (Entry<Long, byte[]> v : pixelsIdThumbnails.entrySet()) {
                    imageIdThumbnails.put(
                        pixelsIdImageIds.get(v.getKey()),
                        v.getValue()
                    );
                }
                return imageIdThumbnails;
            } finally {
                t0.stop();
            }
        } finally {
            thumbnailStore.close();
        }
    }

}
