package bisq.core.api;

import bisq.core.api.model.UriConnection;
import bisq.core.xmr.connection.MoneroConnectionsManager;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroRpcConnection;

@Slf4j
@Singleton
class CoreMoneroConnectionsService {

    private final MoneroConnectionsManager connectionManager;

    @Inject
    public CoreMoneroConnectionsService(MoneroConnectionsManager connectionManager) {
        this.connectionManager = connectionManager;
    }


    void addConnection(UriConnection connection) {
        connectionManager.addConnection(toMoneroRpcConnection(connection));
    }

    void removeConnection(String connectionUri) {
        connectionManager.removeConnection(connectionUri);
    }

    void removeConnection(UriConnection connection) {
        removeConnection(connection.getUri());
    }

    UriConnection getConnection() {
        return toUriConnection(connectionManager.getConnection());
    }

    List<UriConnection> getConnections() {
        return connectionManager.getConnections().stream().map(CoreMoneroConnectionsService::toUriConnection).collect(Collectors.toList());
    }

    void setConnection(String connectionUri) {
        connectionManager.setConnection(connectionUri);
    }

    void setConnection(UriConnection connection) {
        connectionManager.setConnection(toMoneroRpcConnection(connection));
    }

    UriConnection checkConnection() {
        return toUriConnection(connectionManager.checkConnection());
    }

    List<UriConnection> checkConnections() {
        return connectionManager.checkConnections().stream().map(CoreMoneroConnectionsService::toUriConnection).collect(Collectors.toList());
    }

    void startCheckingConnection(Long refreshPeriod) {
        connectionManager.startCheckingConnection(refreshPeriod);
    }

    void stopCheckingConnection() {
        connectionManager.stopCheckingConnection();
    }

    UriConnection getBestAvailableConnection() {
        return toUriConnection(connectionManager.getBestAvailableConnection());
    }

    void setAutoSwitch(boolean autoSwitch) {
        connectionManager.setAutoSwitch(autoSwitch);
    }

    private static UriConnection toUriConnection(MoneroRpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        return UriConnection.builder()
                .uri(rpcConnection.getUri())
                .priority(rpcConnection.getPriority())
                .onlineStatus(toOnlineStatus(rpcConnection.isOnline()))
                .authenticationStatus(toAuthenticationStatus(rpcConnection.isAuthenticated()))
                .build();
    }

    private static UriConnection.AuthenticationStatus toAuthenticationStatus(Boolean authenticated) {
        if (authenticated == null) return UriConnection.AuthenticationStatus.NO_AUTHENTICATION;
        else if (authenticated) return UriConnection.AuthenticationStatus.AUTHENTICATED;
        else return UriConnection.AuthenticationStatus.NOT_AUTHENTICATED;
    }

    private static UriConnection.OnlineStatus toOnlineStatus(Boolean online) {
        if (online == null) return UriConnection.OnlineStatus.UNKNOWN;
        else if (online) return UriConnection.OnlineStatus.ONLINE;
        else return UriConnection.OnlineStatus.OFFLINE;
    }

    private static MoneroRpcConnection toMoneroRpcConnection(UriConnection uriConnection) {
        if (uriConnection == null) return null;
        return new MoneroRpcConnection(uriConnection.getUri(), uriConnection.getUsername(), uriConnection.getPassword()).setPriority(uriConnection.getPriority());
    }
}
