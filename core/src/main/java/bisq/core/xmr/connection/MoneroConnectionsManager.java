package bisq.core.xmr.connection;

import static com.google.common.base.Preconditions.checkState;

import bisq.common.UserThread;
import bisq.core.api.CoreAccountService;
import bisq.core.btc.model.EncryptedConnectionList;
import bisq.core.btc.setup.DownloadListener;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.crypto.ScryptUtil;
import com.google.common.util.concurrent.Service.State;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroConnectionManagerListener;
import monero.common.MoneroRpcConnection;
import monero.daemon.MoneroDaemon;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroPeer;

@Slf4j
@Singleton
public final class MoneroConnectionsManager {

    // TODO: this connection manager should update app status, don't poll in WalletsSetup every 30 seconds
    private static final int MIN_BROADCAST_CONNECTIONS = 2;
    private static final long DAEMON_REFRESH_PERIOD_MS = 15000L; // check connection periodically in ms
    private static final long DAEMON_INFO_POLL_PERIOD_MS = 20000L; // collect daemon info periodically in ms

    // TODO (woodser): support each network type, move to config, remove localhost authentication
    private static final List<MoneroRpcConnection> DEFAULT_CONNECTIONS = Arrays.asList(
            new MoneroRpcConnection("http://localhost:38081", "superuser", "abctesting123").setPriority(1), // localhost is first priority
            new MoneroRpcConnection("http://haveno.exchange:38081", "", "").setPriority(2)
    );

    private final Object lock = new Object();
    private final WalletsSetup walletsSetup;
    private final MoneroConnectionManager connectionManager;
    private final EncryptedConnectionList connectionList;
    private final ObjectProperty<List<MoneroPeer>> peers = new SimpleObjectProperty<>();
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();
    
    private boolean isInitialized = false;
    private MoneroDaemon daemon;

    @Inject
    public MoneroConnectionsManager(WalletsSetup walletsSetup,
                                    CoreAccountService accountService,
                                    MoneroConnectionManager connectionManager,
                                    EncryptedConnectionList connectionList) {
        this.walletsSetup = walletsSetup;
        this.connectionManager = connectionManager;
        this.connectionList = connectionList;
        
        // listen for account updates
        accountService.addListener(accountService.new AccountServiceListener() {
            @Override
            public void onAccountCreated() {
                System.out.println("MoneroConnectionsManager.accountservice.onAccountCreated()");
                connectionList.initializeEncryption(ScryptUtil.getKeyCrypterScrypt()); // TODO: necessary if they're already loaded?
                initializeOnce(); // TODO: reset isInitialized if account closed or deleted
            }
            
            @Override
            public void onAccountOpened() {
                try {
                    System.out.println("MoneroConnectionsManager.accountservice.onAccountOpened()");
                    connectionList.initializeEncryption(ScryptUtil.getKeyCrypterScrypt()); // TODO: necessary if they're already loaded?
                    initializeOnce();
//                    BlockingQueue<Integer> blockingQueue = new ArrayBlockingQueue<Integer>(1); // TODO: integer parameter type is placeholder
//                    System.out.println("Created blocking queue, reading persisted...");
//                    connectionList.readPersisted(new Runnable() { // TODO: only readPersisted once
//                        @Override
//                        public void run() {
//                            System.out.println("readPersisted() called run handler!");
//                            initializeOnce();
//                            blockingQueue.offer(0);
//                        }
//                    });
//                    System.out.println("Waiting for block to take!");
//                    blockingQueue.take();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e); // TODO: proper error handling
                }
            }
            
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                System.out.println("MoneroConnectionsManager.accountservice.onPasswordChanged(" + oldPassword + ", " + newPassword + ")");
                connectionList.changePassword(oldPassword, newPassword);
            }
        });
    }

    private void initializeOnce() {
        System.out.println("MoneroConnectionsManager.initializeOnce()");
        synchronized (lock) {
            if (isInitialized) {
                System.out.println("Already initialized, ignoring");
                return;
            }

            // load connections
            connectionList.getConnections().forEach(connectionManager::addConnection);
            for (MoneroRpcConnection connection : connectionList.getConnections()) {
                System.out.println("Read decrypted connection from disk: " + connection);
            }

            // add default connections
            for (MoneroRpcConnection connection : DEFAULT_CONNECTIONS) {
                if (connectionList.hasConnection(connection.getUri())) continue;
                addConnection(connection);
            }

            // restore last used connection
            connectionList.getCurrentConnectionUri().ifPresentOrElse(connectionManager::setConnection, () -> {
                connectionManager.setConnection(DEFAULT_CONNECTIONS.get(0).getUri()); // default to localhost
            });
            daemon = new MoneroDaemonRpc(connectionManager.getConnection());

            // restore configuration
            connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
            long refreshPeriod = connectionList.getRefreshPeriod();
            if (refreshPeriod > 0) connectionManager.startCheckingConnection(refreshPeriod);
            else if (refreshPeriod == 0) connectionManager.startCheckingConnection(DAEMON_REFRESH_PERIOD_MS);
            else checkConnection();

            // register connection change listener
            connectionManager.addListener(this::onConnectionChanged);
            
            // update daemon info periodically
            updateDaemonInfo();
            UserThread.runPeriodically(() -> {
                updateDaemonInfo();
            }, DAEMON_INFO_POLL_PERIOD_MS / 1000l);
            isInitialized = true;
        }
    }
    
    // ------------------------ CONNECTION MANAGEMENT -------------------------
    
    public MoneroDaemon getDaemon() {
        State state = walletsSetup.getWalletConfig().state();
        checkState(state == State.STARTING || state == State.RUNNING, "Cannot call until startup is complete");
        return this.daemon;
    }
    
    public void addListener(MoneroConnectionManagerListener listener) {
        synchronized (lock) {
            connectionManager.addListener(listener);
        }
    }

    public void addConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            connectionList.addConnection(connection);
            connectionManager.addConnection(connection);
        }
    }

    public void removeConnection(String uri) {
        synchronized (lock) {
            connectionList.removeConnection(uri);
            connectionManager.removeConnection(uri);
        }
    }

    public MoneroRpcConnection getConnection() {
        synchronized (lock) {
            return connectionManager.getConnection();
        }
    }

    public List<MoneroRpcConnection> getConnections() {
        synchronized (lock) {
            return connectionManager.getConnections();
        }
    }

    public void setConnection(String connectionUri) {
        synchronized (lock) {
            connectionManager.setConnection(connectionUri); // listener will update connection list
        }
    }

    public void setConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            connectionManager.setConnection(connection); // listener will update connection list
        }
    }

    public MoneroRpcConnection checkConnection() {
        synchronized (lock) {
            connectionManager.checkConnection();
            return getConnection();
        }
    }

    public List<MoneroRpcConnection> checkConnections() {
        synchronized (lock) {
            connectionManager.checkConnections();
            return getConnections();
        }
    }

    public void startCheckingConnection(Long refreshPeriod) {
        synchronized (lock) {
            connectionManager.startCheckingConnection(refreshPeriod == null ? DAEMON_REFRESH_PERIOD_MS : refreshPeriod);
            connectionList.setRefreshPeriod(refreshPeriod);
        }
    }

    public void stopCheckingConnection() {
        synchronized (lock) {
            connectionManager.stopCheckingConnection();
            connectionList.setRefreshPeriod(-1L);
        }
    }

    public MoneroRpcConnection getBestAvailableConnection() {
        synchronized (lock) {
            return connectionManager.getBestAvailableConnection();
        }
    }

    public void setAutoSwitch(boolean autoSwitch) {
        synchronized (lock) {
            connectionManager.setAutoSwitch(autoSwitch);
            connectionList.setAutoSwitch(autoSwitch);
        }
    }
    
    // ----------------------------- APP METHODS ------------------------------
    
    public boolean isChainHeightSyncedWithinTolerance() {
        if (daemon == null) return false;
        Long peersChainHeight = daemon.getSyncInfo().getTargetHeight();
        if (peersChainHeight == 0) return true; // monero-daemon-rpc sync_info's target_height returns 0 when node is fully synced
        long bestChainHeight = chainHeight.get();
        if (Math.abs(peersChainHeight - bestChainHeight) <= 3) {
            return true;
        }
        log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), peersChainHeight);
        return false;
    }
    
    private void updateDaemonInfo() {
        System.out.println("MoneroConnectionsManager.updateDaemonInfo()!!!");
        try {
            if (daemon == null) throw new RuntimeException("No daemon connection"); // TODO: this is expected until initial connection set
            peers.set(getOnlinePeers());
            numPeers.set(peers.get().size());
            chainHeight.set(daemon.getHeight());
        } catch (Exception e) {
            log.warn("Could not update daemon info: " + e.getMessage());
        }
    }

    private List<MoneroPeer> getOnlinePeers() {
        return daemon.getPeers().stream()
                .filter(peer -> peer.isOnline())
                .collect(Collectors.toList());
    }
    
    public ReadOnlyIntegerProperty numPeersProperty() {
        return numPeers;
    }

    public ReadOnlyObjectProperty<List<MoneroPeer>> peerConnectionsProperty() {
        return peers;
    }
    
    public boolean hasSufficientPeersForBroadcast() {
        return numPeers.get() >= getMinBroadcastConnections();
    }

    public LongProperty chainHeightProperty() {
        return chainHeight;
    }
    
    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return downloadListener.percentageProperty();
    }
    
    public int getMinBroadcastConnections() {
        return MIN_BROADCAST_CONNECTIONS;
    }
    
    public boolean isDownloadComplete() {
        return downloadPercentageProperty().get() == 1d;
    }
    
    // ------------------------------- HELPERS --------------------------------
    
    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        synchronized (lock) {
            if (currentConnection == null) {
                daemon = null;
                connectionList.setCurrentConnectionUri(null);
            } else {
                daemon = new MoneroDaemonRpc(connectionManager.getConnection());
                connectionList.removeConnection(currentConnection.getUri());
                connectionList.addConnection(currentConnection);
                connectionList.setCurrentConnectionUri(currentConnection.getUri());
            }
        }
    }
}
