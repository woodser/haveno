package bisq.core.xmr.connection;

import bisq.core.api.CoreAccountService;
import bisq.core.btc.model.EncryptedConnectionList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroConnectionManagerListener;
import monero.common.MoneroRpcConnection;

@Slf4j
@Singleton
public final class MoneroConnectionsManager {

    // TODO: this connection manager should update app status, don't poll in WalletsSetup every 30 seconds
    private static final long DEFAULT_REFRESH_PERIOD = 15_000L; // check the connection every 15 seconds per default

    // TODO (woodser): support each network type, move to config, remove localhost authentication
    private static final List<MoneroRpcConnection> DEFAULT_CONNECTIONS = Arrays.asList(
            new MoneroRpcConnection("http://localhost:38081", "superuser", "abctesting123").setPriority(1), // localhost is first priority
            new MoneroRpcConnection("http://haveno.exchange:38081", "", "").setPriority(2)
    );

    private final Object lock = new Object();
    private final MoneroConnectionManager connectionManager;
    private final EncryptedConnectionList connectionList;

    @Inject
    public MoneroConnectionsManager(CoreAccountService accountService,
                                    MoneroConnectionManager connectionManager,
                                    EncryptedConnectionList connectionList) {
        this.connectionManager = connectionManager;
        this.connectionList = connectionList;
        
        // listen for account updates
        accountService.addListener(accountService.new AccountServiceListener() {
            @Override
            public void onAccountCreated() {
                System.out.println("MoneroConnectionsManager.accountservice.onAccountCreated()");
                initialize();
                // TODO: handle
            }
            
            @Override
            public void onAccountOpened() {
                System.out.println("MoneroConnectionsManager.accountservice.onAccountOpened()");
                initialize();
                // TODO: handle
            }
            
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                System.out.println("MoneroConnectionsManager.accountservice.onPasswordChanged(" + oldPassword + ", " + newPassword + ")");
                // TODO: handle
                // TODO: EncryptionConnectionList.setPassword(newPassword) instead of it listening to account service directly?
            }
        });
    }

    public void initialize() {
        synchronized (lock) {

            // load connections
            connectionList.getConnections().forEach(connectionManager::addConnection);

            // add default connections
            for (MoneroRpcConnection connection : DEFAULT_CONNECTIONS) {
                if (connectionList.hasConnection(connection.getUri())) continue;
                addConnection(connection);
            }

            // restore last used connection
            connectionList.getCurrentConnectionUri().ifPresentOrElse(connectionManager::setConnection, () -> {
                connectionManager.setConnection(DEFAULT_CONNECTIONS.get(0).getUri()); // default to localhost
            });

            // restore configuration
            connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
            long refreshPeriod = connectionList.getRefreshPeriod();
            if (refreshPeriod > 0) connectionManager.startCheckingConnection(refreshPeriod);
            else if (refreshPeriod == 0) connectionManager.startCheckingConnection(DEFAULT_REFRESH_PERIOD);
            else checkConnection();

            // register connection change listener
            connectionManager.addListener(this::onConnectionChanged);
        }
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
            connectionManager.startCheckingConnection(refreshPeriod == null ? DEFAULT_REFRESH_PERIOD : refreshPeriod);
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
    
    // ------------------------------- HELPERS --------------------------------
    
    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        synchronized (lock) {
            if (currentConnection == null) {
                connectionList.setCurrentConnectionUri(null);
            } else {
                connectionList.removeConnection(currentConnection.getUri());
                connectionList.addConnection(currentConnection);
                connectionList.setCurrentConnectionUri(currentConnection.getUri());
            }
        }
    }
}
