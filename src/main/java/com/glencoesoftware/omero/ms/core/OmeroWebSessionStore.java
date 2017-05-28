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
import java.util.concurrent.CompletionStage;

/**
 * An OMERO.web session store.
 * @author Chris Allan <callan@glencoesoftware.com>
 *
 */
public interface OmeroWebSessionStore extends Closeable {

    /**
     * Retrieve the OMERO.web session's current
     * <code>omeroweb.connector.Connector</code> synchronously.
     * @param sessionKey Session key to retrieve a connector for.
     * @return Connector instance or <code>null</code> if session lookup fails.
     */
    IConnector getConnector(String sessionKey);

    /**
     * Retrieve the OMERO.web session's current
     * <code>omeroweb.connector.Connector</code>.
     * @param sessionKey Session key to retrieve a connector for.
     * @return A new {@link CompletionStage} that, when the {@link IConnector}
     * retrieval is complete is executed.
     */
    CompletionStage<IConnector> getConnectorAsync(String sessionKey);
}
