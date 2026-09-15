package haveno.core.util;

import ch.qos.logback.classic.LoggerContext;
import haveno.common.crypto.Encryption;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.common.util.Utilities;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.setup.MoneroWalletRpcManager;
import haveno.core.xmr.wallet.WalletPasswordChange;
import java.io.Console;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import monero.common.MoneroUtils;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletFull;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroWalletConfig;
import org.slf4j.LoggerFactory;

/** Repairs interrupted password changes offline using credentials supplied at hidden prompts. */
public final class RecoverPassword {

    private static final String RECOVERY_COMMAND = "java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword <network-data-directory> [monero-wallet-rpc-path]";

    private RecoverPassword() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || "--help".equals(args[0])) {
            System.out.println("Close Haveno and back up its data, then run: " + RECOVERY_COMMAND);
            System.out.println("The directory is xmr_mainnet, xmr_local or xmr_stagenet. The optional executable selects wallet RPC instead of the native library.");
            return;
        }
        try {
            // disable library wire logging before any recovery password can be sent over RPC
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        } catch (LinkageError | ClassCastException e) {
            System.err.println("Recovery cannot run with incompatible libraries. Run it with the built all-in-one jar: " + RECOVERY_COMMAND);
            System.exit(1);
            return;
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run in a terminal with hidden password input; passwords cannot be supplied as arguments");
        console.printf("Close Haveno and its wallet processes and back up the data directory before continuing.%n");
        console.printf("Recovery restores wallets and connection credentials to the current account password. Unset passwords are tried automatically.%n");
        String current = readPassword(console, "Current account password (empty if unset): ");
        List<String> candidates = new ArrayList<>();
        while (true) {
            String attempted = readPassword(console, "Password used during a failed change (empty when finished): ");
            if (attempted == null) break;
            if (!candidates.contains(attempted)) candidates.add(attempted);
        }
        try {
            recover(Path.of(args[0]), current, candidates, args.length == 2 ? Path.of(args[1]) : null);
            console.printf("Recovery completed. Start Haveno with the current account password.%n");
        } catch (Exception e) {
            console.printf("Recovery did not finish: %s%nKeep all passwords. After resolving the error, rerun this tool with every password used during the failed changes.%n", e.getMessage());
            System.exit(1);
        } finally {
            candidates.clear();
        }
    }

    private static String readPassword(Console console, String prompt) {
        char[] value = console.readPassword(prompt);
        if (value == null) throw new IllegalStateException("Password input cancelled");
        try {
            return value.length == 0 ? null : new String(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    public static void recover(Path networkDir, String currentPassword, List<String> attemptedPasswords, Path rpcExecutable) throws Exception {
        networkDir = networkDir.toAbsolutePath().normalize();
        MoneroNetworkType network;
        switch (networkDir.getFileName().toString()) {
            case "xmr_mainnet": network = MoneroNetworkType.MAINNET; break;
            case "xmr_local":
            case "xmr_testnet": network = MoneroNetworkType.TESTNET; break;
            case "xmr_stagenet": network = MoneroNetworkType.STAGENET; break;
            default: throw new IllegalArgumentException("Expected an xmr_mainnet, xmr_local or xmr_stagenet data directory");
        }
        Path walletDir = networkDir.resolve("wallet");
        if (!Files.isDirectory(networkDir.resolve("keys")) || !Files.isDirectory(walletDir)) {
            throw new IllegalArgumentException("Expected a network data directory containing keys and wallet directories");
        }
        KeyStorage storage = new KeyStorage(networkDir.resolve("keys").toFile());
        storage.checkKeyFiles();
        KeyRing ring = new KeyRing(storage);
        List<String> candidates = new ArrayList<>();
        candidates.add(currentPassword == null || currentPassword.isEmpty() ? null : currentPassword);
        for (String password : attemptedPasswords) {
            String candidate = password == null || password.isEmpty() ? null : password;
            if (!candidates.contains(candidate)) candidates.add(candidate);
        }
        if (!candidates.contains(null)) candidates.add(null);
        String target = candidates.get(0);
        MoneroWalletRpcManager manager = new MoneroWalletRpcManager();
        MoneroWalletRpc rpc = null;
        try {
            if (!ring.unlockKeys(target, false)) throw new IllegalStateException("Could not unlock account keys");
            List<Path> wallets;
            try (var files = Files.list(walletDir)) {
                wallets = files.filter(path -> path.getFileName().toString().endsWith(".keys"))
                        .filter(path -> !path.getFileName().toString().equals("haveno_XMR_seed_validation.keys"))
                        .sorted(Comparator.comparing(path -> !path.getFileName().toString().equals("haveno_XMR.keys")))
                        .toList();
            }

            // close every probe before opening a native wallet; closing another descriptor releases its POSIX lock
            for (Path keys : wallets) {
                try (FileChannel channel = FileChannel.open(keys, StandardOpenOption.READ, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IllegalStateException("A wallet is open. Close Haveno and its wallet processes before recovery");
                }
            }

            // validate and stage connection credentials before changing any wallet
            Path connectionFile = networkDir.resolve("db/EncryptedConnectionList");
            protobuf.PersistableEnvelope connections = null;
            if (Files.exists(connectionFile)) {
                protobuf.PersistableEnvelope stored = PersistenceManager.readEncrypted(connectionFile.toFile(), ring.getSymmetricKey());
                if (stored == null || !stored.hasEncryptedConnectionList()) throw new IOException("Invalid stored connection list; restore it from backup");
                EncryptedConnectionList list = EncryptedConnectionList.fromProto(stored.getEncryptedConnectionList());
                list.reconcilePasswords(candidates, target);
                connections = (protobuf.PersistableEnvelope) list.toProtoMessage();
            } else if (Files.exists(networkDir.resolve("db/" + FileUtil.CORRUPTED_BACKUP_FOLDER + "/EncryptedConnectionList"))) {
                throw new IOException("Stored connections were quarantined. Restore db/EncryptedConnectionList from a readable backup before recovery");
            }

            if (!wallets.isEmpty() && rpcExecutable == null) MoneroUtils.tryLoadNativeLibrary();
            if (!wallets.isEmpty() && (rpcExecutable != null || !MoneroUtils.isNativeLibraryLoaded())) {
                Path binary = rpcExecutable == null ? networkDir.getParent().resolve(Utilities.isWindows() ? "monero-wallet-rpc.exe" : "monero-wallet-rpc") : rpcExecutable;
                if (!Files.isRegularFile(binary)) throw new IOException("Native wallet library unavailable; supply the installed monero-wallet-rpc executable as the second argument");
                List<String> cmd = new ArrayList<>(Arrays.asList(binary.toAbsolutePath().toString(), "--offline", "--rpc-bind-ip", "127.0.0.1",
                        "--rpc-login", "recovery:" + UUID.randomUUID(), "--wallet-dir", walletDir.toString()));
                if (network != MoneroNetworkType.MAINNET) cmd.add(network == MoneroNetworkType.TESTNET ? "--testnet" : "--stagenet");
                rpc = manager.startInstance(cmd);
                rpc.stopSyncing();
            }
            List<String> walletPasswords = candidates.stream().map(WalletPasswordChange::normalizePassword).distinct().toList();
            for (Path keys : wallets) {
                String name = keys.getFileName().toString();
                Path path = keys.resolveSibling(name.substring(0, name.length() - ".keys".length()));
                changeWalletPassword(path, network, rpc, walletPasswords, WalletPasswordChange.normalizePassword(target));
            }
            if (connections != null) {
                FileUtil.rollingBackup(connectionFile.getParent().toFile(), connectionFile.getFileName().toString(), 20);
                byte[] encrypted = Encryption.encryptPayloadWithHmac(connections.toByteArray(), ring.getSymmetricKey());
                FileUtil.writeAtomically(connectionFile, encrypted);
            }
            storage.finishPasswordChange(ring, target, candidates);
            FileUtil.deleteDirectory(walletDir.resolve("backup").toFile(), null, false);
        } finally {
            ring.lockKeys();
            candidates.clear();
            if (rpc != null) manager.stopInstance(rpc, null, true);
        }
    }

    private static void changeWalletPassword(Path path, MoneroNetworkType network, MoneroWalletRpc rpc, List<String> candidates, String target) {
        MoneroWalletConfig config = new MoneroWalletConfig().setPath(rpc == null ? path.toString() : path.getFileName().toString()).setNetworkType(network);
        MoneroWallet wallet = WalletPasswordChange.<MoneroWallet>open(candidates, password -> {
            config.setPassword(password);
            if (rpc == null) return MoneroWalletFull.openWallet(config);
            rpc.openWallet(config);
            return rpc;
        });
        Throwable failure = null;
        try {
            WalletPasswordChange.change(wallet, candidates, target);
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            try {
                wallet.close(false);
            } catch (Exception e) {
                if (failure == null) throw e;
                failure.addSuppressed(e);
            }
        }
    }
}
