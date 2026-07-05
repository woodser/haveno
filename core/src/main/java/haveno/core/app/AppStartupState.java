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

package haveno.core.app;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import haveno.core.api.XmrConnectionService;
import haveno.core.api.CoreNotificationService;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.common.UserThread;
import haveno.network.p2p.BootstrapListener;
import haveno.network.p2p.P2PService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

/**
 * We often need to wait until network and wallet is ready or other combination of startup states.
 * To avoid those repeated checks for the state or setting of listeners on different domains we provide here a
 * collection of useful states.
 */
@Slf4j
@Singleton
public class AppStartupState {
    // Do not convert to local field as there have been issues observed that the object got GC'ed.
    private final MonadicBinding<Boolean> p2pNetworkAndWalletInitialized;

    private final BooleanProperty walletAndNetworkReady = new SimpleBooleanProperty();
    private final BooleanProperty allDomainServicesInitialized = new SimpleBooleanProperty();
    private final BooleanProperty applicationFullyInitialized = new SimpleBooleanProperty();
    private final BooleanProperty updatedDataReceived = new SimpleBooleanProperty();
    private final BooleanProperty isBlockDownloadComplete = new SimpleBooleanProperty();
    private final BooleanProperty wasWalletSynced = new SimpleBooleanProperty();
    private final BooleanProperty hasSufficientPeersForBroadcast = new SimpleBooleanProperty();

    @Inject
    public AppStartupState(CoreNotificationService notificationService,
                           XmrConnectionService xmrConnectionService,
                           XmrWalletService xmrWalletService,
                           P2PService p2PService) {

        p2PService.addP2PServiceListener(new BootstrapListener() {
            @Override
            public void onDataReceived() {
                onP2pBootstrapComplete("onDataReceived");
            }

            @Override
            public void onUpdatedDataReceived() {
                onP2pBootstrapComplete("onUpdatedDataReceived");
            }

            // The P2P network can finish bootstrapping without a full initial data download: when no
            // seed node or no peers are available it still reports the network as initialized. See
            // P2PNetworkSetup, which sets p2pNetworkInitialized=true on onDataReceived, onNoSeedNodeAvailable
            // and onNoPeersAvailable alike (and the app proceeds to init domain services on that basis).
            // We must treat those paths the same here, otherwise updatedDataReceived stays false forever
            // on daemons that bootstrap via the no-seed/no-peers path (e.g. when started before the seed
            // nodes are serving data), so the app never reports initialized even though everything else
            // (wallet synced, block download complete, domain services) is ready.
            @Override
            public void onNoSeedNodeAvailable() {
                onP2pBootstrapComplete("onNoSeedNodeAvailable");
            }

            @Override
            public void onNoPeersAvailable() {
                onP2pBootstrapComplete("onNoPeersAvailable");
            }

            private void onP2pBootstrapComplete(String source) {
                UserThread.execute(() -> {
                    if (!updatedDataReceived.get()) log.warn("[READINESS-TRACE WOMBATTRACE] server P2P bootstrap complete via {} -> setting updatedDataReceived=true", source);
                    updatedDataReceived.set(true);
                });
            }
        });

        xmrConnectionService.downloadPercentageProperty().addListener((observable, oldValue, newValue) -> {
            if (xmrConnectionService.isDownloadComplete())
                isBlockDownloadComplete.set(true);
        });

        xmrConnectionService.numUpdatesProperty().addListener((observable, oldValue, newValue) -> {
            if (xmrConnectionService.isDownloadComplete())
                isBlockDownloadComplete.set(true);
        });

        xmrWalletService.downloadPercentageProperty().addListener((observable, oldValue, newValue) -> {
            wasWalletSynced.set(xmrWalletService.wasWalletSynced());
        });

        xmrConnectionService.numConnectionsProperty().addListener((observable, oldValue, newValue) -> {
            if (xmrConnectionService.hasSufficientPeersForBroadcast())
                hasSufficientPeersForBroadcast.set(true);
        });

        p2pNetworkAndWalletInitialized = EasyBind.combine(updatedDataReceived,
                isBlockDownloadComplete,
                wasWalletSynced,
                hasSufficientPeersForBroadcast, // TODO: consider sufficient number of peers?
                allDomainServicesInitialized,
                (a, b, c, d, e) -> {
                    boolean prevReady = walletAndNetworkReady.get();
                    if (a && b && c) {
                        if (!prevReady) log.warn("[READINESS-TRACE WOMBATTRACE] server walletAndNetworkReady: false -> TRUE (updatedDataReceived={}, isBlockDownloadComplete={}, wasWalletSynced={}, thread={})", a, b, c, Thread.currentThread().getName());
                        walletAndNetworkReady.set(true);
                    } else if (!wasWalletSynced()) {
                        if (prevReady) log.warn("[READINESS-TRACE WOMBATTRACE] server walletAndNetworkReady: TRUE -> false (updatedDataReceived={}, isBlockDownloadComplete={}, wasWalletSynced(mirror)={}, thread={}) -- clients that already believe the app is initialized will now be rejected", a, b, c, Thread.currentThread().getName());
                        walletAndNetworkReady.set(false);
                    }
                    log.info("[READINESS-TRACE WOMBATTRACE] Combined initialized state = {} = updatedDataReceived={} && isBlockDownloadComplete={} && isWalletSynced={} && hasSufficientPeersForBroadcast={} && allDomainServicesInitialized={} (walletAndNetworkReady={}, thread={})", (a && b && c && d && e), updatedDataReceived.get(), isBlockDownloadComplete.get(), wasWalletSynced.get(), hasSufficientPeersForBroadcast.get(), allDomainServicesInitialized.get(), walletAndNetworkReady.get(), Thread.currentThread().getName());
                    return a && b && c && e;
                });
        p2pNetworkAndWalletInitialized.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                applicationFullyInitialized.set(true);
                notificationService.sendAppInitializedNotification();
                log.warn("[READINESS-TRACE WOMBATTRACE] server reporting APP_INITIALIZED=TRUE to clients (walletAndNetworkReady={}, thread={}) -- clients waiting on awaitAppInitialized() will now proceed", walletAndNetworkReady.get(), Thread.currentThread().getName());
            } else {
                applicationFullyInitialized.set(false);
                notificationService.sendAppInitializedNotification();
                log.warn("[READINESS-TRACE WOMBATTRACE] server reporting APP_INITIALIZED=false to clients (walletAndNetworkReady={}, thread={}) -- note: same APP_INITIALIZED notification type is sent whether true or false", walletAndNetworkReady.get(), Thread.currentThread().getName());
            }
        });
    }

    public void onDomainServicesInitialized() {
        UserThread.execute(() -> allDomainServicesInitialized.set(true));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isWalletAndNetworkReady() {
        return walletAndNetworkReady.get();
    }

    public ReadOnlyBooleanProperty walletAndNetworkReadyProperty() {
        return walletAndNetworkReady;
    }

    public boolean isAllDomainServicesInitialized() {
        return allDomainServicesInitialized.get();
    }

    public ReadOnlyBooleanProperty allDomainServicesInitializedProperty() {
        return allDomainServicesInitialized;
    }

    public boolean isApplicationFullyInitialized() {
        return applicationFullyInitialized.get();
    }

    public ReadOnlyBooleanProperty applicationFullyInitializedProperty() {
        return applicationFullyInitialized;
    }

    public boolean isUpdatedDataReceived() {
        return updatedDataReceived.get();
    }

    public ReadOnlyBooleanProperty updatedDataReceivedProperty() {
        return updatedDataReceived;
    }

    public boolean isBlockDownloadComplete() {
        return isBlockDownloadComplete.get();
    }

    public boolean wasWalletSynced() {
        return wasWalletSynced.get();
    }

    public ReadOnlyBooleanProperty isBlockDownloadCompleteProperty() {
        return isBlockDownloadComplete;
    }

    public boolean isHasSufficientPeersForBroadcast() {
        return hasSufficientPeersForBroadcast.get();
    }

    public ReadOnlyBooleanProperty hasSufficientPeersForBroadcastProperty() {
        return hasSufficientPeersForBroadcast;
    }

}
