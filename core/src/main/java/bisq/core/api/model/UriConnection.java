package bisq.core.api.model;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class UriConnection {

    public enum OnlineStatus {
        UNKNOWN,
        ONLINE,
        OFFLINE;
    }

    public enum AuthenticationStatus {
        NO_AUTHENTICATION,
        AUTHENTICATED,
        NOT_AUTHENTICATED
    }

    @NonNull String uri;
    String username;
    String password;
    int priority;
    OnlineStatus onlineStatus;
    AuthenticationStatus authenticationStatus;
}
