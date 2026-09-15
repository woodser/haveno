/*
 * This file is part of Haveno.
 * See LICENSE for licensing information.
 */
package haveno.core.xmr.wallet;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import monero.common.MoneroError;
import monero.common.MoneroRpcError;
import monero.wallet.MoneroWallet;

/** Idempotent operations shared by password changes and interrupted-change recovery. */
public final class WalletPasswordChange {
    private WalletPasswordChange() {}

    public static String normalizePassword(String password) {
        return password == null || password.isEmpty() ? "password" : password;
    }

    public static void change(MoneroWallet wallet, List<String> candidates, String target) {
        RuntimeException failure = null;
        for (String candidate : candidates) {
            try {
                wallet.changePassword(candidate, target);
                wallet.save();
                return;
            } catch (Exception e) {
                failure = asRuntimeException(e);
                if (!isPasswordError(e)) throw failure;
            }
        }
        throw failure;
    }

    public static <T> T open(List<String> candidates, Function<String, T> opener) {
        RuntimeException failure = null;
        for (String candidate : candidates) {
            try {
                return opener.apply(candidate);
            } catch (Exception e) {
                failure = asRuntimeException(e);
                if (!isPasswordError(e)) throw failure;
            }
        }
        throw failure;
    }

    public static boolean isPasswordError(Throwable error) {
        if (error instanceof MoneroRpcError && Integer.valueOf(-22).equals(((MoneroRpcError) error).getCode())) return true;
        String message = error.getMessage();
        return message != null && (message.toLowerCase(Locale.ROOT).contains("invalid password")
                || message.toLowerCase(Locale.ROOT).contains("invalid original password"));
    }

    private static RuntimeException asRuntimeException(Exception error) {
        // native JNI methods can throw checked exceptions despite their Java declarations
        return error instanceof RuntimeException ? (RuntimeException) error : new MoneroError(error);
    }

}
