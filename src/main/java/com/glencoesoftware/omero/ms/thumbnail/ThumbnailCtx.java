package com.glencoesoftware.omero.ms.thumbnail;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import com.glencoesoftware.omero.ms.core.OmeroRequestCtx;

import io.vertx.core.MultiMap;

public class ThumbnailCtx extends OmeroRequestCtx {


    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ThumbnailCtx.class);

    /** The size of the longest side of the thumbnail */
    public Integer longestSide;

    /** Image ID */
    public Long imageId;

    /** Image IDs" */
    public List<Long> imageIds;

    /** Rendering Definition ID */
    public Long renderingDefId;

    /**
     * Constructor for jackson to decode the object from string
     */
    ThumbnailCtx() {};

    /**
     * Default constructor.
     * @param params {@link io.vertx.core.http.HttpServerRequest} parameters
     * required for rendering an image region.
     * @param omeroSessionKey OMERO session key.
     */
    ThumbnailCtx(MultiMap params, String omeroSessionKey) {
        this.omeroSessionKey = omeroSessionKey;
        assignParams(params);
    }

    public void assignParams(MultiMap params) throws IllegalArgumentException {
        this.longestSide = Optional.ofNullable(params.get("longestSide"))
                .map(Integer::parseInt)
                .orElse(96);

        this.imageIds = params.getAll("id").stream()
                .map(Long::parseLong)
                .collect(Collectors.toList());

        this.imageId = Optional.ofNullable(params.get("imageId"))
                .map(Long::parseLong)
                .orElse(null);

        if(this.imageIds.size() == 0 && this.imageId == null) {
            throw new IllegalArgumentException("No Image IDs provided.");
        }

        this.renderingDefId = Optional.ofNullable(params.get("rdefId"))
        .map(Long::parseLong).orElse(null);
    }
}
