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

package haveno.core.crypto;

import com.google.protobuf.ByteString;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.IncorrectPasswordException;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.file.FileUtil;
import haveno.common.persistence.PersistenceManager;
import haveno.core.api.AccountServiceListener;
import haveno.core.api.CoreAccountService;
import haveno.core.api.XmrConnectionService;
import haveno.core.app.HavenoExecutable;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.core.user.User;
import haveno.core.util.RecoverPassword;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.model.XmrAddressEntryList;
import haveno.core.xmr.setup.WalletsSetup;
import haveno.core.xmr.wallet.XmrWalletBase;
import haveno.core.xmr.wallet.XmrWalletService;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.wallet.MoneroWallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class EncryptionTest {
    private KeyRing keyRing;
    private File dir;
    private KeyStorage keyStorage;
    private XmrWalletService previousWalletService;
    private TradeManager previousTradeManager;
    private final List<CoreAccountService> accounts = new ArrayList<>();

    @BeforeEach
    public void setup() throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException, CryptoException {

        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        previousWalletService = HavenoUtils.xmrWalletService;
        previousTradeManager = HavenoUtils.tradeManager;
        keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
    }

    @AfterEach
    public void tearDown() throws IOException {
        accounts.forEach(CoreAccountService::onShutDownStarted);
        HavenoUtils.xmrWalletService = previousWalletService;
        HavenoUtils.tradeManager = previousTradeManager;
        FileUtil.deleteDirectory(dir);
    }

    private CoreAccountService account(String password) throws Exception {
        CoreAccountService account = new CoreAccountService(null, keyStorage, new KeyRing(keyStorage));
        account.openAccount(password);
        ready(account);
        return account;
    }

    private void ready(CoreAccountService account) throws Exception {
        accounts.add(account);
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS, (oldPassword, newPassword) -> {});
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS, (oldPassword, newPassword) -> {});
        account.onPersistedDataRead();
    }

    @Test
    public void testSetChangeAndRemovePassword() throws Exception {
        CoreAccountService account = account(null);
        List<String> seen = new ArrayList<>();
        account.addListener(new AccountServiceListener() {
            @Override
            public void onPasswordChanged(String oldPassword, String newPassword) {
                assertEquals(newPassword, account.getPassword());
                seen.add(newPassword);
            }
        });
        account.changePassword(null, "first-password");
        assertEquals("first-password", account("first-password").getPassword());
        account.changePassword("first-password", "second-password");
        assertEquals("second-password", account("second-password").getPassword());
        account.changePassword("second-password", "");
        assertNull(account(null).getPassword());
        assertEquals(Arrays.asList("first-password", "second-password", null), seen);
    }

    @Test
    public void testInvalidPasswordsAreRejectedBeforeParticipants() throws Exception {
        CoreAccountService account = account(null);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        assertThrows(IllegalStateException.class, () -> account.changePassword("wrong-password", "new-password"));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "short"));
        assertThrows(IllegalArgumentException.class, () -> account.changePassword(null, "password-\u00e9"));
        verify(listener, never()).onPasswordChanged(any(), any());
        assertNull(account(null).getPassword());
    }

    @Test
    public void testReopeningAccountVerifiesPassword() throws Exception {
        CoreAccountService account = account("");
        assertThrows(IncorrectPasswordException.class, () -> account.openAccount("wrong-password"));
        assertNull(account.getPassword());
        account.closeAccount();
        account.openAccount("");
        assertNull(account.getPassword());
    }

    @Test
    public void testFailedChangeRequiresOfflineRecoveryWithoutRollback() throws Exception {
        CoreAccountService account = account(null);
        AtomicReference<String> actual = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> actual.set(newPassword));
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                (oldPassword, newPassword) -> { throw new IllegalStateException("injected failure"); });
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("password recovery"));
        assertTrue(account.isPasswordRecoveryRequired());
        assertEquals("new-password", actual.get());
        assertNull(account.getPassword());
        assertNull(account(null).getPassword());
        assertThrows(IllegalStateException.class, account::checkPasswordRecovery);
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "another-password"));
        assertThrows(IllegalStateException.class, () -> account.withAccountBackup(() -> { throw new AssertionError("export ran"); }));
        assertEquals("new-password", actual.get());
    }

    @Test
    public void testCreatingAccountWithEmptyPasswordUsesUnsetPassword() throws Exception {
        KeyStorage storage = new KeyStorage(Files.createDirectory(new File(dir, "empty-password").toPath()).toFile());
        CoreAccountService account = new CoreAccountService(null, storage, new KeyRing(storage));
        account.createAccount("");
        ready(account);
        assertNull(account.getPassword());
        account.changePassword("", "new-password");
        CoreAccountService restarted = new CoreAccountService(null, storage, new KeyRing(storage));
        restarted.openAccount("new-password");
        assertEquals("new-password", restarted.getPassword());
    }

    @Test
    public void testLoginReportsRecoveryFailureThroughItsFuture() throws Exception {
        CoreAccountService account = mock(CoreAccountService.class);
        doReturn(true).when(account).accountExists();
        IllegalStateException failure = new IllegalStateException("Account key cannot be loaded");
        doThrow(failure).when(account).openAccount(null);
        HavenoExecutable executable = mock(HavenoExecutable.class, CALLS_REAL_METHODS);
        setField(HavenoExecutable.class, executable, "accountService", account);
        Method login = HavenoExecutable.class.getDeclaredMethod("loginAccount");
        login.setAccessible(true);
        CompletableFuture<?> result = (CompletableFuture<?>) login.invoke(executable);
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test
    public void testKeystoreCommitFailureRequiresRecovery() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doThrow(new IllegalStateException("injected disk failure")).when(failing).commitPasswordChange(any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AtomicReference<String> walletPassword = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> walletPassword.set(newPassword));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertEquals("new-password", walletPassword.get());
        assertTrue(account.isPasswordRecoveryRequired());
        assertNull(account(null).getPassword());
    }

    @Test
    public void testBackupCleanupFailureLeavesNewPasswordUsable() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doThrow(new IllegalStateException("backup is read-only")).when(failing).finishPasswordChange(any(), any(), any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AccountServiceListener listener = mock(AccountServiceListener.class);
        account.addListener(listener);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("Password changed successfully"));
        assertFalse(account.isPasswordRecoveryRequired());
        assertEquals("new-password", account.getPassword());
        assertEquals("new-password", account("new-password").getPassword());
        assertThrows(IncorrectPasswordException.class, () -> account(null));
        verify(listener).onPasswordChanged(null, "new-password");
        verify(listener, never()).onPasswordChangeFailed();
    }

    @Test
    public void testWalletBackupsAreRemovedOnlyAfterSuccessfulChange() throws Exception {
        CoreAccountService account = account(null);
        walletService(account);
        Path backups = Files.createDirectories(dir.toPath().resolve("backup/backups_haveno_XMR_keys"));
        Path oldWallet = Files.writeString(backups.resolve("old_haveno_XMR.keys"), "old wallet keys");
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.CONNECTIONS,
                (oldPassword, newPassword) -> { throw new IllegalStateException("injected failure"); });
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(Files.exists(oldWallet));

        CoreAccountService restarted = account(null);
        walletService(restarted);
        restarted.changePassword(null, "new-password");
        assertFalse(Files.exists(oldWallet));
        assertEquals("new-password", account("new-password").getPassword());
    }

    @Test
    public void testServicesMustBeReadyBeforePasswordWrites() throws Exception {
        CoreAccountService account = account(null);
        setField(CoreAccountService.class, account, "persistedDataRead", false);
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertFalse(account.isPasswordRecoveryRequired());
        account.onPersistedDataRead();
        account.onShutDownStarted();
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertFalse(account.isPasswordRecoveryRequired());
    }

    @Test
    public void testCommitFailureAfterReplacementRequiresRecovery() throws Exception {
        KeyStorage failing = spy(keyStorage);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected failure after replacement");
        }).when(failing).commitPasswordChange(any());
        CoreAccountService account = new CoreAccountService(null, failing, new KeyRing(keyStorage));
        account.openAccount(null);
        ready(account);
        AtomicReference<String> participant = new AtomicReference<>();
        account.addPasswordChangeHandler(CoreAccountService.PasswordChangeTarget.WALLETS,
                (oldPassword, newPassword) -> participant.set(newPassword));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(error.getMessage().contains("Keep both passwords"));
        assertNull(account.getPassword());
        assertEquals("new-password", participant.get());
        assertTrue(account.isPasswordRecoveryRequired());
        assertThrows(IncorrectPasswordException.class, () -> account(null));
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "another-password"));
        assertEquals("new-password", account("new-password").getPassword());
    }

    @SuppressWarnings("unchecked")
    private EncryptedConnectionList connectionList(CoreAccountService account, protobuf.EncryptedConnectionList persisted,
                                                  PersistenceManager<EncryptedConnectionList> persistence) {
        doAnswer(invocation -> {
            if (persisted == null) ((Runnable) invocation.getArgument(1)).run();
            else ((Consumer<EncryptedConnectionList>) invocation.getArgument(0)).accept(EncryptedConnectionList.fromProto(persisted));
            return null;
        }).when(persistence).readPersisted(any(), any());
        EncryptedConnectionList list = new EncryptedConnectionList(persistence, account);
        list.readPersisted(() -> {});
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testConnectionCredentialsRecoverWithExplicitPasswordsAfterRestart() throws Exception {
        CoreAccountService account = account(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.addConnection(new MoneroRpcConnection("http://localhost:18082"));
        list.changePassword(null, "new-password");
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        EncryptedConnectionList recovered = connectionList(account(null), stored, mock(PersistenceManager.class));
        assertTrue(assertThrows(IllegalStateException.class, recovered::getConnections).getMessage().contains("recovery tool"));
        recovered.reconcilePasswords(Arrays.asList(null, "new-password"), null);
        assertEquals("daemon-secret", recovered.getConnections().stream()
                .filter(connection -> connection.getUri().endsWith("18081")).findFirst().orElseThrow().getPassword());
        assertNull(recovered.getConnections().stream()
                .filter(connection -> connection.getUri().endsWith("18082")).findFirst().orElseThrow().getPassword());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvalidConnectionCredentialDoesNotPartiallyReplaceList() throws Exception {
        CoreAccountService account = account(null);
        PersistenceManager<EncryptedConnectionList> persistence = mock(PersistenceManager.class);
        EncryptedConnectionList list = connectionList(account, null, persistence);
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "secret"));
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        protobuf.EncryptedConnectionList corrupt = stored.toBuilder().addItems(stored.getItems(0).toBuilder()
                .setUrl("http://localhost:18082").setEncryptedPassword(ByteString.copyFrom(new byte[1]))).build();
        EncryptedConnectionList loaded = connectionList(account, corrupt, mock(PersistenceManager.class));
        byte[] before = loaded.toProtoMessage().toByteArray();
        assertThrows(IllegalStateException.class, () -> loaded.changePassword(null, "new-password"));
        assertArrayEquals(before, loaded.toProtoMessage().toByteArray());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testWrongRecoveryCandidateDoesNotOverwriteConnectionCredentials() throws Exception {
        CoreAccountService account = account(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.changePassword(null, "unknown-password");
        protobuf.EncryptedConnectionList stored = ((protobuf.PersistableEnvelope) list.toProtoMessage()).getEncryptedConnectionList();
        EncryptedConnectionList recovered = EncryptedConnectionList.fromProto(stored);
        assertThrows(IllegalStateException.class, () -> recovered.reconcilePasswords(Arrays.asList(null, "wrong-password"), null));
        assertEquals(stored, ((protobuf.PersistableEnvelope) recovered.toProtoMessage()).getEncryptedConnectionList());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOfflineRecoveryPreservesFilesUntilCredentialsCanBeReconciled() throws Exception {
        Path network = Files.createDirectory(dir.toPath().resolve("xmr_mainnet"));
        Files.createDirectory(network.resolve("wallet"));
        Files.createDirectory(network.resolve("db"));
        KeyStorage storage = new KeyStorage(Files.createDirectory(network.resolve("keys")).toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        CoreAccountService account = new CoreAccountService(null, storage, ring);
        account.openAccount(null);
        EncryptedConnectionList list = connectionList(account, null, mock(PersistenceManager.class));
        list.addConnection(new MoneroRpcConnection("http://localhost:18081", "user", "daemon-secret"));
        list.changePassword(null, "attempted-password");
        Path connectionFile = network.resolve("db/EncryptedConnectionList");
        byte[] encrypted = haveno.common.crypto.Encryption.encryptPayloadWithHmac(list.toProtoMessage().toByteArray(), ring.getSymmetricKey());
        Files.write(connectionFile, encrypted);
        byte[] wrapper = Files.readAllBytes(network.resolve("keys/sym.p12"));

        assertThrows(IncorrectPasswordException.class, () -> RecoverPassword.recover(network, "wrong-password", List.of("attempted-password"), null));
        assertThrows(IllegalStateException.class, () -> RecoverPassword.recover(network, null, List.of("wrong-password"), null));
        assertArrayEquals(encrypted, Files.readAllBytes(connectionFile));
        assertArrayEquals(wrapper, Files.readAllBytes(network.resolve("keys/sym.p12")));

        RecoverPassword.recover(network, null, List.of("attempted-password"), null);
        assertArrayEquals(wrapper, Files.readAllBytes(network.resolve("keys/sym.p12")));
        protobuf.PersistableEnvelope repaired = PersistenceManager.readEncrypted(connectionFile.toFile(), ring.getSymmetricKey());
        EncryptedConnectionList reopened = connectionList(account, repaired.getEncryptedConnectionList(), mock(PersistenceManager.class));
        assertEquals("daemon-secret", reopened.getConnections().get(0).getPassword());
    }

    @Test
    public void testOfflineRecoveryAcceptsApplicationNetworkDirectories() throws Exception {
        for (BaseCurrencyNetwork network : BaseCurrencyNetwork.values()) {
            Path networkDir = Files.createDirectory(dir.toPath().resolve(network.name().toLowerCase(Locale.ROOT)));
            Files.createDirectory(networkDir.resolve("wallet"));
            KeyStorage storage = new KeyStorage(Files.createDirectory(networkDir.resolve("keys")).toFile());
            KeyRing ring = new KeyRing(storage, null, true);
            byte[] wrapper = Files.readAllBytes(networkDir.resolve("keys/sym.p12"));
            ring.lockKeys();

            RecoverPassword.recover(networkDir, null, List.of(), null);

            assertArrayEquals(wrapper, Files.readAllBytes(networkDir.resolve("keys/sym.p12")));
        }
    }

    private XmrWalletService walletService(CoreAccountService account) throws Exception {
        Constructor<XmrWalletService> constructor = XmrWalletService.class.getDeclaredConstructor(User.class, Preferences.class,
                CoreAccountService.class, XmrConnectionService.class, WalletsSetup.class, XmrAddressEntryList.class, File.class, int.class);
        constructor.setAccessible(true);
        HavenoUtils.tradeManager = mock(TradeManager.class);
        return constructor.newInstance(mock(User.class), mock(Preferences.class), account, mock(XmrConnectionService.class),
                mock(WalletsSetup.class), mock(XmrAddressEntryList.class), dir, 0);
    }

    @Test
    public void testLiveWalletChangesWithoutReopening() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mock(MoneroWallet.class);
        doAnswer(invocation -> {
            if (!"password".equals(invocation.getArgument(0))) throw new MoneroError("Invalid original password.");
            return null;
        }).when(wallet).changePassword(anyString(), anyString());
        service.changeWalletPassword("retained", wallet, "new-password");
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
    }

    @Test
    public void testUnknownWalletPasswordAndSaveFailureAreNotIgnored() throws Exception {
        XmrWalletService service = walletService(account(null));
        MoneroWallet wallet = mock(MoneroWallet.class);
        doThrow(new MoneroError("Invalid original password.")).when(wallet).changePassword(anyString(), anyString());
        assertThrows(MoneroError.class, () -> service.changeWalletPassword("retained", wallet, "new-password"));
        verify(wallet, never()).save();
        XmrWalletService failingService = walletService(account(null));
        MoneroWallet failingSave = mock(MoneroWallet.class);
        MoneroError error = new MoneroError("disk full");
        doThrow(error).when(failingSave).save();
        assertSame(error, assertThrows(MoneroError.class, () -> failingService.changeWalletPassword("retained", failingSave, "new-password")));
    }

    @Test
    public void testWalletPasswordsFollowEachWalletDuringChange() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet first = mock(MoneroWallet.class);
        MoneroWallet second = mock(MoneroWallet.class);
        doReturn("first").when(first).getPath();
        doReturn("second").when(second).getPath();
        Files.createFile(dir.toPath().resolve("first.keys"));
        Files.createFile(dir.toPath().resolve("second.keys"));
        Trade firstTrade = mock(Trade.class);
        Trade secondTrade = mock(Trade.class);
        doReturn("first").when(firstTrade).getWalletName();
        doReturn("second").when(secondTrade).getWalletName();
        doReturn(List.of(firstTrade, secondTrade)).when(HavenoUtils.tradeManager).getAllTrades();
        doAnswer(invocation -> {
            assertEquals("password", service.getWalletPassword(first));
            service.changeWalletPassword("first", first, "new-password");
            return null;
        }).when(firstTrade).changeWalletPassword(any());
        doAnswer(invocation -> {
            assertEquals("new-password", service.getWalletPassword(first));
            assertEquals("password", service.getWalletPassword(second));
            verify(second, never()).changePassword(any(), any());
            service.changeWalletPassword("second", second, "new-password");
            return null;
        }).when(secondTrade).changeWalletPassword(any());
        account.changePassword(null, "new-password");
        assertEquals("new-password", service.getWalletPassword(first));
        assertEquals("new-password", service.getWalletPassword(second));
        verify(first).changePassword("password", "new-password");
        verify(second).changePassword("password", "new-password");
        verify(first, never()).close(anyBoolean());
        verify(second, never()).close(anyBoolean());
    }

    @Test
    public void testPasswordChangeAllowsBackgroundRefresh() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mock(MoneroWallet.class);
        Files.createFile(dir.toPath().resolve("haveno_XMR.keys"));
        setField(XmrWalletBase.class, service, "wallet", wallet);
        setField(XmrWalletBase.class, service, "backgroundRefreshWallet", wallet);
        setField(XmrWalletBase.class, service, "backgroundRefreshLatch", new CountDownLatch(1));
        account.changePassword(null, "new-password");
        assertEquals("new-password", account("new-password").getPassword());
        assertFalse(account.isPasswordRecoveryRequired());
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
    }

    @Test
    public void testPasswordChangeWaitsForMainWalletReplacementBeforeSnapshot() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet restored = mock(MoneroWallet.class);
        doReturn(dir.toPath().resolve("haveno_XMR").toString()).when(restored).getPath();
        doAnswer(invocation -> {
            if (!"password".equals(invocation.getArgument(0))) throw new MoneroError("Invalid original password.");
            return null;
        }).when(restored).changePassword(anyString(), anyString());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread changing = new Thread(() -> {
            try {
                account.changePassword(null, "new-password");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            synchronized (service.getWalletLock()) {
                // restore has removed the old main wallet and is about to move the replacement into place
                Path restoreKeys = Files.createFile(dir.toPath().resolve("haveno_XMR_restore.keys"));
                changing.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (changing.getState() != Thread.State.BLOCKED && changing.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(Thread.State.BLOCKED, changing.getState());
                Files.move(restoreKeys, dir.toPath().resolve("haveno_XMR.keys"));
                setField(XmrWalletBase.class, service, "wallet", restored);
            }
        } finally {
            changing.join(10000);
        }
        assertFalse(changing.isAlive());
        assertNull(failure.get());
        verify(restored).changePassword("password", "new-password");
        verify(restored).save();
        assertEquals("new-password", account("new-password").getPassword());
    }

    @Test
    public void testStoppedTradeChangesRetainedWalletWithoutOpeningTrade() throws Exception {
        Trade trade = mock(Trade.class, CALLS_REAL_METHODS);
        setField(XmrWalletBase.class, trade, "walletLock", new Object());
        setField(XmrWalletBase.class, trade, "isShutDownStarted", true);
        XmrWalletService service = mock(XmrWalletService.class);
        setField(Trade.class, trade, "xmrWalletService", service);
        doReturn(true).when(trade).walletExists();
        doReturn("trade").when(trade).getShortId();
        doReturn("uid").when(trade).getShortUid();
        trade.changeWalletPassword("new-password");
        verify(service).changeWalletPassword(anyString(), eq(null), eq("new-password"));
        verify(trade, never()).getWallet();
        assertTrue(trade.isShutDownStarted());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPendingNativeCloseMustFinishSuccessfullyBeforeReopen() throws Exception {
        XmrWalletService service = walletService(account(null));
        Field field = XmrWalletService.class.getDeclaredField("pendingWalletCloses");
        field.setAccessible(true);
        Map<String, Future<?>> pending = (Map<String, Future<?>>) field.get(service);
        Method wait = XmrWalletService.class.getDeclaredMethod("awaitPendingWalletClose", String.class);
        wait.setAccessible(true);
        Future<?> closing = mock(Future.class);
        doThrow(new TimeoutException("still closing")).when(closing).get(anyLong(), any());
        pending.put("wallet", closing);
        assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
        assertSame(closing, pending.get("wallet"));
        Future<?> failed = CompletableFuture.failedFuture(new MoneroError("native release failed"));
        pending.put("wallet", failed);
        assertThrows(InvocationTargetException.class, () -> wait.invoke(service, "wallet"));
        assertSame(failed, pending.get("wallet"));
        pending.put("wallet", CompletableFuture.completedFuture(null));
        wait.invoke(service, "wallet");
        assertFalse(pending.containsKey("wallet"));
    }

    @Test
    public void testFailedMainWalletCloseRetainsHandleForForceClose() throws Exception {
        XmrWalletService service = walletService(account(null));
        MoneroWallet wallet = mock(MoneroWallet.class);
        setField(XmrWalletBase.class, service, "wallet", wallet);
        doThrow(new MoneroError("close failed")).when(wallet).close(true);
        Method close = XmrWalletService.class.getDeclaredMethod("closeMainWallet", boolean.class);
        close.setAccessible(true);
        assertEquals(false, close.invoke(service, false));
        Field handle = XmrWalletBase.class.getDeclaredField("wallet");
        handle.setAccessible(true);
        assertSame(wallet, handle.get(service));
        Method forceClose = XmrWalletService.class.getDeclaredMethod("forceCloseMainWallet");
        forceClose.setAccessible(true);
        forceClose.invoke(service);
        verify(wallet).close(false);
        assertNull(handle.get(service));
    }

    @Test
    public void testFailedMainWalletPasswordChangeStopsPolling() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = walletService(account);
        MoneroWallet wallet = mock(MoneroWallet.class);
        TaskLooper poller = mock(TaskLooper.class);
        setField(XmrWalletBase.class, service, "wallet", wallet);
        setField(XmrWalletService.class, service, "pollLooper", poller);
        Files.createFile(dir.toPath().resolve("haveno_XMR.keys"));
        doThrow(new MoneroError("disk full")).when(wallet).changePassword(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> account.changePassword(null, "new-password"));
        assertTrue(account.isPasswordRecoveryRequired());
        verify(poller).stop();
    }

    @Test
    public void testRestorePasswordFailureRetainsMainWalletForShutdown() throws Exception {
        CoreAccountService account = account(null);
        XmrWalletService service = spy(walletService(account));
        MoneroWallet wallet = mock(MoneroWallet.class);
        setField(XmrWalletBase.class, service, "wallet", wallet);
        Files.createFile(dir.toPath().resolve("haveno_XMR.keys"));
        Files.createFile(dir.toPath().resolve("haveno_XMR_restore.keys"));
        doAnswer(invocation -> {
            account.requirePasswordRecovery();
            throw new MoneroError("restore password change failed");
        }).when(service).changeWalletPassword(eq("haveno_XMR_restore"), eq(null), any());
        Method change = XmrWalletService.class.getDeclaredMethod("changeWalletPasswords", String.class, String.class);
        change.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> change.invoke(service, null, "new-password"));
        verify(wallet).changePassword("password", "new-password");
        verify(wallet).save();
        verify(wallet, never()).close(anyBoolean());
        assertTrue(account.isPasswordRecoveryRequired());
        Method close = XmrWalletService.class.getDeclaredMethod("closeMainWallet", boolean.class);
        close.setAccessible(true);
        assertEquals(true, close.invoke(service, false));
        verify(wallet).close(true);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
