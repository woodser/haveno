/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.settings.network;

import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;
import monero.daemon.MoneroDaemon;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;

@Slf4j
public class MoneroNetworkListItem {
    private final MoneroRpcConnection connection;
    private MoneroDaemonInfo info;
    
    public MoneroNetworkListItem(MoneroRpcConnection connection) {
        this.connection = connection;
        try {
            MoneroDaemon node = new MoneroDaemonRpc(connection);
            this.info = node.getInfo();
        } catch (Exception e) {
            log.warn("Unable to fetch info from Monero node: " + connection.getUri());
        }
    }

    public String getOnionAddress() {
        return connection.getUri();
    }

    public String getVersion() {
        return info == null ? "" :info.getVersion().split("-")[0];
    }

    public String getSubVersion() {
        if (info == null) return "";
        String[] parts = info.getVersion().split("-");
        return parts.length > 1 ? parts[1] : "";
    }

    public String getHeight() {
        return info == null ? "" : String.valueOf(info.getHeight());
    }
}
