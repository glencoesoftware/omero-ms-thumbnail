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

import java.util.Arrays;
import java.util.Map;

import omero.model.Image;

/**
 * OMERO session aware handler whose event handler method conforms to the
 * {@link OmeroRequestHandler} interface. This class is expected to be used as
 * a lambda handler.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class ThumbnailRequestHandler extends ThumbnailsRequestHandler {

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
        Map<Long, byte[]> thumbnails = renderThumbnails(client);
        if (thumbnails == null) {
            return null;
        }
        return thumbnails.get(imageIds.get(0));
    }

}
