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

package haveno.core.api;

import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.user.Preferences.NetworkSettings;
import haveno.core.user.Preferences.UseTorForXmr;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.nodes.XmrNodes;
import haveno.core.xmr.nodes.XmrNodes.MoneroNodesOption;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.network.Socks5ProxyProvider;
import haveno.network.p2p.P2PService;
import monero.common.MoneroRpcConnection;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class XmrConnectionServiceTest {
    private XmrConnectionService previousService;
    private Preferences previousPreferences;
    private XmrWalletService previousWalletService;
    private XmrConnectionService service;

    @BeforeEach
    public void setUp() {
        previousService = HavenoUtils.xmrConnectionService;
        previousPreferences = HavenoUtils.preferences;
        previousWalletService = HavenoUtils.xmrWalletService;
        HavenoUtils.xmrWalletService = null; // a syncing wallet skips the poll request
    }

    @AfterEach
    public void tearDown() {
        if (service != null) {
            service.onShutDownStarted();
            service.shutDown();
        }
        HavenoUtils.xmrConnectionService = previousService;
        HavenoUtils.preferences = previousPreferences;
        HavenoUtils.xmrWalletService = previousWalletService;
    }

    @Test
    public void nodeEditsDoNotWaitForThePolledNodeRequest() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket node = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread unresponsive = new Thread(() -> {
                try (Socket request = node.accept()) {
                    requested.countDown();
                    done.await(); // accept the poll request without answering it
                } catch (Exception e) {
                    // closed by the test
                }
            });
            unresponsive.setDaemon(true);
            unresponsive.start();

            MoneroRpcConnection polled = new MoneroRpcConnection("http://127.0.0.1:" + node.getLocalPort());
            Preferences preferences = mock(Preferences.class);
            when(preferences.getMoneroNodesOption()).thenReturn(MoneroNodesOption.CUSTOM);
            when(preferences.getEffectiveNetworkSettings()).thenReturn(new NetworkSettings(MoneroNodesOption.CUSTOM, UseTorForXmr.OFF, ""));
            when(preferences.getUseTorForXmr()).thenReturn(UseTorForXmr.OFF);
            EncryptedConnectionList connectionList = mock(EncryptedConnectionList.class);
            when(connectionList.getCurrentConnectionUri()).thenReturn(Optional.of(polled.getUri()));
            CoreAccountService accountService = mock(CoreAccountService.class);
            when(accountService.isAccountOpen()).thenReturn(true);
            service = new XmrConnectionService(mock(P2PService.class), new Config(), mock(CoreContext.class), preferences, mock(WalletsSetup.class),
                    accountService, mock(XmrNodes.class), mock(XmrLocalNode.class), connectionList, mock(Socks5ProxyProvider.class));
            service.addConnection(polled);
            setField("isInitialized", true);
            setField("monerod", new MoneroDaemonRpc(polled));
            Method startPolling = XmrConnectionService.class.getDeclaredMethod("startPolling", MoneroDaemonInfo.class);
            startPolling.setAccessible(true);
            startPolling.invoke(service, (MoneroDaemonInfo) null);

            assertTrue(requested.await(10, TimeUnit.SECONDS));
            assertNotNull(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.addCustomNode("127.0.0.1:1")));
        } finally {
            tearDown(); // stop polling before the request is released
            done.countDown();
        }
    }

    private void setField(String name, Object value) throws ReflectiveOperationException {
        Field field = XmrConnectionService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
