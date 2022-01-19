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
    
    @Getter
    private String password;
    private List<AccountServiceListener> listeners = new ArrayList<AccountServiceListener>();
    private Runnable accountOpenedHandler;
    private Consumer<Runnable> accountDeletedHandler;
    private Consumer<Runnable> accountRestoredHandler;
    
    /**
     * Account listener default class.
     */
    public class AccountServiceListener {
        public void onAccountOpened() {}
        public void onAccountRestored() {}
        public void onAccountDeleted() {}
        public void onPasswordChanged(String oldPassword, String newPassword) {}
    }

    @Inject
    public CoreAccountService(Config config, KeyStorage keyStorage, KeyRing keyRing) {
        this.config = config;
        this.keyStorage = keyStorage;
        this.keyRing = keyRing;
    }
    
    public void addListener(AccountServiceListener listener) {
        listeners.add(listener);
    }
    
    public boolean removeListener(AccountServiceListener listener) {
        return listeners.remove(listener);
    }
    
    public boolean accountExists() {
        return keyStorage.allKeyFilesExist(); // public and private key pair indicate the existence of the account
    }
    
    public boolean isAccountOpen() {
        return keyRing.isUnlocked() && accountExists();
    }
    
    public void createAccount(String password) {
        if (accountExists()) throw new IllegalStateException("Cannot create account if account already exists");
        keyRing.generateKeys(password);
        keyStorage.saveKeyRing(keyRing, password);
        setPassword(password); // TODO: update wallet passwords
        if (accountOpenedHandler != null) accountOpenedHandler.run();
    }
    
    public void openAccount(String password) throws IncorrectPasswordException {
        if (!accountExists()) throw new IllegalStateException("Cannot open account if account does not exist");
        try {
            if (keyRing.unlockKeys(password, false)) {
                if (accountOpenedHandler != null) accountOpenedHandler.run();
            }
        } catch (IncorrectPasswordException ex) {
            log.warn(ex.getMessage());
        }
    }
    
    private void setPassword(String newPassword) {
        String oldPassword = this.password; // TODO: need default password?
        this.password = newPassword;
        for (AccountServiceListener listener : listeners) listener.onPasswordChanged(oldPassword, newPassword);
    }
    
    
    // TODO: remove these
    
    public void setAccountDeletedHandler(Consumer<Runnable> handler) {
        accountDeletedHandler = handler;
    }

    public void setAccountRestoredHandler(Consumer<Runnable> handler) {
        accountRestoredHandler = handler;
    }

    public void setAccountOpenedHandler(Runnable handler) {
        accountOpenedHandler = handler;
    }

    public void clearAccountOpenHandlers() {
        accountOpenedHandler = null;
    }
    
    
    

    /**
     * Backup the account to a zip file. Throw error if !accountExists().
     * @return InputStream with the zip of the account.
     */
    public void backupAccount(int bufferSize, Consumer<InputStream> consume, Consumer<Exception> error) {
        if (!accountExists()) {
            throw new IllegalStateException("Cannot backup non existing account");
        }

        // Flush all known persistence objects to disk.
        PersistenceManager.flushAllDataToDiskAtBackup(() -> {
            // Pipe the serialized account object to stream which will be read by the consumer.
            try {
                File dataDir = new File(config.appDataDir.getPath());
                PipedInputStream in = new PipedInputStream(bufferSize);
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
            } catch (java.io.IOException ex) {
                error.accept(ex);
            }
        });
    }

    /**
     * Change the Haveno account password. Throw error if !isAccountOpen().
     * @param password
     */
    public void changePassword(String password) {
        if (!isAccountOpen()) {
            throw new IllegalStateException("Cannot change password on unopened account");
        }

        // Encrypt the keyring with the new password.
        this.password = password;
        keyStorage.saveKeyRing(keyRing, password);
        // TODO: Override the wallet password
        //walletsService.setWalletPassword(password, null);
    }

    /**
     * Close the currently open account. Throw error if !isAccountOpen().
     * @throws Exception
     */
    public void closeAccount() {
        if (!isAccountOpen()) {
            throw new IllegalStateException("Cannot close unopened account");
        }

        // Closed account means the keys are locked.
        keyRing.lockKeys();
    }

    /**
     * Permanently delete the Haveno account.
     */
    public void deleteAccount(Runnable onShutdown) {
        try {
            keyRing.lockKeys();
            File dataDir = new File(config.appDataDir.getPath());
            FileUtil.deleteDirectory(dataDir, null, false);
            if (accountDeletedHandler != null) {
                log.info("Calling deleteAccount handler");
                accountDeletedHandler.accept(onShutdown);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Open existing account. Throw error if `!accountExists()
     * @param password The password for the account.
     */



    /**
     * Restore the account from a zip file. Throw error if accountExists().
     * @param inputStream
     * @throws Exception
     */
    public void restoreAccount(InputStream inputStream, int bufferSize, Runnable onShutdown) throws Exception {
        if (accountExists()) {
            throw new IllegalStateException("Cannot restore account if there is an existing account");
        }

        File dataDir = new File(config.appDataDir.getPath());
        ZipUtil.unzipToDir(dataDir, inputStream, bufferSize);
        if (accountRestoredHandler != null)
            accountRestoredHandler.accept(onShutdown);
    }

}
