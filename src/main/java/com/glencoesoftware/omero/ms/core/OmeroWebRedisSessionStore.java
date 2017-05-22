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
import org.python.core.Py;
import org.python.core.PyDictionary;
import org.python.core.util.StringUtil;
import org.python.modules.cPickle;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;

/**
 * A Redis backed OMERO.web session store. Based on a provided session key,
 * retrieves the Python pickled session and then utilizes Jython to unpickle
 * the current OMERO.web connector. 
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public class OmeroWebRedisSessionStore implements OmeroWebSessionStore {

    /**
     * Django cache session storage engine key format.
     */
    public static final String KEY_FORMAT =
            "%s:%d:django.contrib.sessions.cache%s";

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(OmeroWebRedisSessionStore.class);

    /** Redis server host */
    private final String host;

    /** Redis server port */
    private final int port;

    /** Redis server password. */
    private final String password;

    /** Redis server database index. */
    private final int dbIndex;

    /**
     * Default constructor.
     * @param host Redis server host.
     * @param port Redis server port.
     * @param password Redis server password.
     * @param dbIndex Redis server database index.
     */
    public OmeroWebRedisSessionStore(
            String host, int port, String password, int dbIndex) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.dbIndex = dbIndex;
    }

    /* (non-Javadoc)
     * @see com.glencoesoftware.omero.ms.core.OmeroWebSessionStore#getConnector(java.lang.String)
     */
    public IConnector getConnector(String sessionKey) {
        byte[] pickledDjangoSession = null;
        StopWatch t0 = new Slf4JStopWatch("getConnector");
        try {
            try (Jedis jedis = new Jedis(host, port)) {
                if (password != null) {
                    jedis.auth(password);
                }
                jedis.select(dbIndex);

                String key = String.format(
                        KEY_FORMAT,
                        "",  // OMERO_WEB_CACHE_KEY_PREFIX
                        1,  // OMERO_WEB_CACHE_VERSION
                        sessionKey);
                // Binary retrieval, get(String) includes a UTF-8 step
                pickledDjangoSession = jedis.get(key.getBytes());
                if (pickledDjangoSession == null) {
                    return null;
                }
            }

            PyDictionary djangoSession = (PyDictionary) cPickle.loads(
                    Py.newString(StringUtil.fromBytes(pickledDjangoSession)));
            log.debug("Session: {}", djangoSession);
            return (IConnector) djangoSession.get("connector");
        } finally {
            t0.stop();
        }
    }

}
