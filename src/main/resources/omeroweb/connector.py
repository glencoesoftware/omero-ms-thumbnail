# -*- coding: utf-8 -*-
#
# Copyright (c) 2017 Glencoe Software, Inc.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#

from com.glencoesoftware.omero.ms.core import IConnector


class Connector(IConnector):
    '''Stub which can be used to unpickle an `omeroweb.connector.Connector`'''

    def getServerId(self):
        return self.server_id

    def getIsSecure(self):
        return self.is_secure

    def getIsPublic(self):
        return self.is_public

    def getOmeroSessionKey(self):
        return self.omero_session_key

    def getUserId(self):
        return self.user_id
 