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
import java.util.concurrent.CompletionStage;

import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.python.core.Py;
import org.python.core.PyDictionary;
import org.python.core.util.StringUtil;
import org.python.modules.cPickle;
import org.slf4j.LoggerFactory;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.RedisAsyncCommands;
import com.lambdaworks.redis.api.sync.RedisCommands;
import com.lambdaworks.redis.codec.ByteArrayCodec;

/**
 * A Redis backed OMERO.web session store. Based on a provided session key,
 * retrieves the Python pickled session and then utilizes Jython to unpickle
 * the current OMERO.web connector. 
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class OmeroWebRedisSessionStore
        implements OmeroWebSessionStore, Closeable {

    /**
     * Django cache session storage engine key format.
     */
    public static final String KEY_FORMAT =
            "%s:%d:django.contrib.sessions.cache%s";

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroWebRedisSessionStore.class);

    /** Redis client */
    private final RedisClient client;

    /** Redis connection */
    private final StatefulRedisConnection<byte[], byte[]> connection;

    /**
     * Default constructor.
     * @param uri Redis connection URI.
     */
    public OmeroWebRedisSessionStore(String uri) {
        client = RedisClient.create(uri);
        connection = client.connect(new ByteArrayCodec());
    }

    /* (non-Javadoc)
     * @see com.glencoesoftware.omero.ms.core.OmeroWebSessionStore#getConnector(java.lang.String)
     */
    public IConnector getConnector(String sessionKey) {
        StopWatch t0 = new Slf4JStopWatch("getConnector");
        try {
            byte[] pickledDjangoSession = null;
            RedisCommands<byte[], byte[]> commands = connection.sync();
            String key = String.format(
                    KEY_FORMAT,
                    "",  // OMERO_WEB_CACHE_KEY_PREFIX
                    1,  // OMERO_WEB_CACHE_VERSION
                    sessionKey);
            // Binary retrieval, get(String) includes a UTF-8 step
            pickledDjangoSession = commands.get(key.getBytes());
            if (pickledDjangoSession == null) {
                return null;
            }

            PyDictionary djangoSession = (PyDictionary) cPickle.loads(
                    Py.newString(StringUtil.fromBytes(pickledDjangoSession)));
            log.debug("Session: {}", djangoSession);
            return (IConnector) djangoSession.get("connector");
        } finally {
            t0.stop();
        }
    }

    /* (non-Javadoc)
     * @see com.glencoesoftware.omero.ms.core.OmeroWebSessionStore#getConnectorAsync(java.lang.String, com.glencoesoftware.omero.ms.core.ConnectorHandler)
     */
    public CompletionStage<IConnector> getConnectorAsync(String sessionKey) {
        RedisAsyncCommands<byte[], byte[]> commands = connection.async();
        String key = String.format(
                KEY_FORMAT,
                "",  // OMERO_WEB_CACHE_KEY_PREFIX
                1,  // OMERO_WEB_CACHE_VERSION
                sessionKey);
        log.debug("Retrieving OMERO.web session with key: {}", key);

        final StopWatch t0 = new Slf4JStopWatch("getConnector");
        // Binary retrieval, get(String) includes a UTF-8 step
        RedisFuture<byte[]> future = commands.get(key.getBytes());
        return future.<IConnector>thenApply(value -> {
            try {
                if (value != null) {
                    PyDictionary djangoSession =
                            (PyDictionary) cPickle.loads(
                                    Py.newString(StringUtil.fromBytes(value)));
                    return (IConnector) djangoSession.get("connector");
                }
            } catch (Exception e) {
                log.error("Exception while unpickling connector", e);
            } finally {
                t0.stop();
            }
            return null;
        });
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }

}
