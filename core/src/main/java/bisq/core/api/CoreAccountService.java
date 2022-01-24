/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.api;

import bisq.common.config.Config;
import bisq.common.crypto.IncorrectPasswordException;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.KeyStorage;
import bisq.common.file.FileUtil;
import bisq.common.persistence.PersistenceManager;
import bisq.common.util.ZipUtil;
import com.google.inject.name.Named;
import java.io.File;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the account state. A created account must have a password which encrypts
 * all persistence in the PersistenceManager. As a result, opening the account requires
 * a correct password to be passed in to deserialize the account properties that are
 * persisted. It is possible to persist the objects without a password (legacy).
 *
 * Backup and restore flushes the persistence objects in the app folder and sends or
 * restores a zip stream.
 */
@Singleton
@Slf4j
public class CoreAccountService {
    
    private final Config config;
    private final KeyStorage keyStorage;
    private final KeyRing keyRing;
    private final File walletDir;
    
    @Getter
    private String password;
    private List<AccountServiceListener> listeners = new ArrayList<AccountServiceListener>();
    
    /**
     * Account listener default class.
     */
    public class AccountServiceListener {
        public void onAccountCreated() {}
        public void onAccountOpened() {}
        public void onAccountRestored(Runnable onShutDown) {}
        public void onAccountDeleted(Runnable onShutDown) {}
        public void onPasswordChanged(String oldPassword, String newPassword) {}
    }
    
    @Inject
    public CoreAccountService(Config config,
                              KeyStorage keyStorage,
                              KeyRing keyRing,
                              @Named(Config.WALLET_DIR) File walletDir) {
        this.config = config;
        this.keyStorage = keyStorage;
        this.keyRing = keyRing;
        this.walletDir = walletDir;
        
        // generate default keys
        keyRing.generateKeys(null);
        keyStorage.saveKeyRing(keyRing, null);
    }
    
    public void addListener(AccountServiceListener listener) {
        try {
            throw new RuntimeException("CoreAccountService.addListener()");
        } catch (Exception e) {
            e.printStackTrace();
        }
        listeners.add(listener);
    }
    
    public boolean removeListener(AccountServiceListener listener) {
        return listeners.remove(listener);
    }
    
    public boolean accountExists() {
        return walletExists("haveno_XMR"); // TODO: config?
    }
    
    private boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + ".keys").exists();
    }
    
    public boolean isAccountOpen() {
        return keyRing.isUnlocked() && accountExists();
    }
    
    public void createAccount(String password) {
        System.out.println("CoreAccountService.createAccount(" + password + ")");
        if (accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
        System.out.println("CoreAccountService 2");
        keyRing.generateKeys(password);
        System.out.println("CoreAccountService 3");
        keyStorage.saveKeyRing(keyRing, password);
        System.out.println("CoreAccountService 4");
        setPassword(password);
        System.out.println("CoreAccountService 5");
        for (AccountServiceListener listener : listeners) listener.onAccountCreated();
        System.out.println("CoreAccountService 6");
    }
    
    public void openAccount(String password) throws IncorrectPasswordException {
        if (!accountExists()) throw new IllegalStateException("Cannot open account if account does not exist");
        try {
            if (keyRing.unlockKeys(password, false)) {
                setPassword(password);
                for (AccountServiceListener listener : listeners) listener.onAccountOpened();
            } else {
                throw new RuntimeException("keyRing.unlockKeys() returned false, that should never happen"); // TODO (woodser): allowable?
            }
        } catch (IncorrectPasswordException ex) {
            log.warn(ex.getMessage());
        }
    }
    
    public void changePassword(String password) {
        if (!isAccountOpen()) throw new IllegalStateException("Cannot change password on unopened account");
        keyStorage.saveKeyRing(keyRing, password);
        String oldPassword = this.password;
        setPassword(password);
        for (AccountServiceListener listener : listeners) listener.onPasswordChanged(oldPassword, password);
    }
    
    public void closeAccount() {
        if (!isAccountOpen()) throw new IllegalStateException("Cannot close unopened account");
        keyRing.lockKeys(); // closed account means the keys are locked
    }
    
    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        if (!accountExists()) throw new IllegalStateException("Cannot backup non existing account");

        // flush all known persistence objects to disk
        PersistenceManager.flushAllDataToDiskAtBackup(() -> {
            try {
                File dataDir = new File(config.appDataDir.getPath());
                PipedInputStream in = new PipedInputStream(bufferSize); // pipe the serialized account object to stream which will be read by the consumer
                PipedOutputStream out = new PipedOutputStream(in);
                log.info("Zipping directory " + dataDir);
                new Thread(() -> {
                    try {
                        ZipUtil.zipDirToStream(dataDir, out, bufferSize);
                    } catch (Exception ex) {
                        error.accept(ex);
                    }
                }).start();
                consume.accept(in);
            } catch (java.io.IOException err) {
                error.accept(err);
            }
        });
    }
    
    public void restoreAccount(InputStream inputStream, int bufferSize, Runnable onShutdown) throws Exception {
        if (accountExists()) throw new IllegalStateException("Cannot restore account if there is an existing account");
        File dataDir = new File(config.appDataDir.getPath());
        ZipUtil.unzipToDir(dataDir, inputStream, bufferSize);
        for (AccountServiceListener listener : listeners) listener.onAccountRestored(onShutdown);
    }
    
    // TODO: flush persistence objects to disk?
    public void deleteAccount(Runnable onShutdown) {
        try {
            keyRing.lockKeys();
            for (AccountServiceListener listener : listeners) listener.onAccountDeleted(onShutdown);
            File dataDir = new File(config.appDataDir.getPath()); // TODO (woodser): deleting directory after gracefulShutdown() so services don't throw when they try to persist (e.g. XmrTxProofService). gracefulShutdown() needs to honor isReadOnly
            FileUtil.deleteDirectory(dataDir, null, false);
        } catch (Exception err) {
            throw new RuntimeException(err);
        }
    }
    
    // ------------------------------- HELPERS --------------------------------
    
    private void setPassword(String newPassword) {
        this.password = newPassword;
        // TODO: update wallet passwords
    }
}
