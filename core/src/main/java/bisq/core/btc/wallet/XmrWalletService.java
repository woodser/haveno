package bisq.core.btc.wallet;

import static com.google.common.base.Preconditions.checkState;

import bisq.common.UserThread;
import bisq.common.config.Config;
import bisq.core.api.CoreAccountService;
import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.listeners.XmrBalanceListener;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.model.XmrAddressEntryList;
import bisq.core.btc.setup.MoneroWalletRpcManager;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.util.ParsingUtils;
import bisq.core.xmr.connection.MoneroConnectionsManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Service.State;
import com.google.inject.name.Named;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import monero.common.MoneroRpcConnection;
import monero.common.MoneroUtils;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWallet;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.MoneroDestination;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroSubaddress;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxQuery;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import monero.wallet.model.MoneroWalletListenerI;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XmrWalletService {
    private static final Logger log = LoggerFactory.getLogger(XmrWalletService.class);

    // Monero configuration
    // TODO: don't hard code configuration, inject into classes?
    private static final MoneroNetworkType MONERO_NETWORK_TYPE = MoneroNetworkType.STAGENET;
    private static final MoneroWalletRpcManager MONERO_WALLET_RPC_MANAGER = new MoneroWalletRpcManager();
    private static final String MONERO_WALLET_RPC_DIR = System.getProperty("user.dir") + File.separator + ".localnet"; // .localnet contains monero-wallet-rpc and wallet files
    private static final String MONERO_WALLET_RPC_PATH = MONERO_WALLET_RPC_DIR + File.separator + "monero-wallet-rpc";
    private static final String DEFAULT_WALLET_PASSWORD = "password"; // only used if account password is null
    private static final String MONERO_WALLET_RPC_USERNAME = "rpc_user";
    private static final String MONERO_WALLET_RPC_PASSWORD = "abc123";
    private static final long MONERO_WALLET_SYNC_RATE = 5000l;

    private final CoreAccountService accountService;
    private final MoneroConnectionsManager connectionManager;
    private final XmrAddressEntryList addressEntryList;
    private final WalletsSetup walletsSetup;
    private final File walletDir;
    private final int rpcBindPort;
    protected final CopyOnWriteArraySet<XmrBalanceListener> balanceListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<MoneroWalletListenerI> walletListeners = new CopyOnWriteArraySet<>();

    private TradeManager tradeManager;
    private MoneroWallet wallet;
    private Map<String, MoneroWallet> multisigWallets;

    @Inject
    XmrWalletService(CoreAccountService accountService,
                     MoneroConnectionsManager connectionManager,
                     WalletsSetup walletsSetup,
                     XmrAddressEntryList addressEntryList,
                     @Named(Config.WALLET_DIR) File walletDir,
                     @Named(Config.WALLET_RPC_BIND_PORT) int rpcBindPort) {
        this.accountService = accountService;
        this.connectionManager = connectionManager;
        this.walletsSetup = walletsSetup;
        this.addressEntryList = addressEntryList;
        this.multisigWallets = new HashMap<String, MoneroWallet>();
        this.walletDir = walletDir;
        this.rpcBindPort = rpcBindPort;
        
        walletsSetup.addSetupCompletedHandler(() -> {
            
            initMainWallet();
            
            accountService.addListener(accountService.new AccountServiceListener() {
                
                @Override
                public void onAccountCreated() {
                    System.out.println("XmrWalletService.accountService.onAccountCreated()");
                    initMainWallet();
                    //xmrAddressEntryList.onWalletReady(walletConfig.getWallet());
                }

                @Override
                public void onAccountOpened() {
                    System.out.println("XmrWalletService.accountService.onAccountOpened()");
                    //xmrAddressEntryList.onWalletReady(walletConfig.getWallet());
                    // TODO: ensure this is called when no password used
                    initMainWallet();
                }
                
                @Override
                public void onAccountClosed() {
                    System.out.println("XmrWalletService.accountService.onAccountClosed()");
                    closeAllWallets();
                }
                
                @Override
                public void onPasswordChanged(String oldPassword, String newPassword) {
                    System.out.println("XmrWalletService.accountservice.onPasswordChanged(" + oldPassword + ", " + newPassword + ")");
                    changeWalletPasswords(oldPassword, newPassword);
                }
            });

//        wallet.addListener(new MoneroWalletListener() {
//            @Override
//            public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) { }
//
//            @Override
//            public void onNewBlock(long height) { }
//
//            @Override
//            public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
//              notifyBalanceListeners();
//            }
//        });
        });
    }
    
    private void initMainWallet() {
      String filePrefix = "haveno"; // TODO: move these to common config with account service
      String xmrPrefix = "_XMR";
      File xmrWalletFile = new File(walletDir, filePrefix + xmrPrefix);
      MoneroWalletConfig walletConfig = new MoneroWalletConfig().setPath(filePrefix + xmrPrefix).setPassword(getWalletPassword());
      wallet = MoneroUtils.walletExists(xmrWalletFile.getPath()) ? openWallet(walletConfig, rpcBindPort) : createWallet(walletConfig, rpcBindPort);
      System.out.println("Monero wallet path: " + wallet.getPath());
      System.out.println("Monero wallet address: " + wallet.getPrimaryAddress());
      System.out.println("Monero wallet uri: " + ((MoneroWalletRpc) wallet).getRpcConnection().getUri());
      wallet.sync(); // blocking
      //downloadListener.doneDownload(); // TODO: need to notify of done download?
      wallet.save();
      System.out.println("Loaded wallet balance: " + wallet.getBalance(0));
      System.out.println("Loaded wallet unlocked balance: " + wallet.getUnlockedBalance(0));
      
      // update wallet connections on change
      connectionManager.addListener(newConnection -> {
          setWalletDaemonConnections(newConnection);
      });
    }
    
    public MoneroWallet getWallet() {
        State state = walletsSetup.getWalletConfig().state();
        checkState(state == State.STARTING || state == State.RUNNING, "Cannot call until startup is complete");
        return wallet;
    }
    
    public MoneroDaemon getDaemon() {
        return connectionManager.getDaemon();
    }
    
    public MoneroConnectionsManager getConnectionManager() {
        return connectionManager;
    }
    
    public String getWalletPassword() {
        return accountService.getPassword() == null ? DEFAULT_WALLET_PASSWORD : accountService.getPassword();
    }

    public boolean walletExists(String walletName) {
        String path = walletDir.toString() + File.separator + walletName;
        return new File(path + ".keys").exists();
    }

    public MoneroWalletRpc createWallet(MoneroWalletConfig config, Integer port) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port);

        // create wallet
        try {
            walletRpc.createWallet(config);
            walletRpc.startSyncing(MONERO_WALLET_SYNC_RATE);
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc, false);
            throw e;
        }
    }

    public MoneroWalletRpc openWallet(MoneroWalletConfig config, Integer port) {

        // start monero-wallet-rpc instance
        MoneroWalletRpc walletRpc = startWalletRpcInstance(port);

        // open wallet
        try {
            System.out.println("OPENING WALLET WITH PASSWORD: " + config.getPassword());
            walletRpc.openWallet(config);
            walletRpc.startSyncing(MONERO_WALLET_SYNC_RATE);
            return walletRpc;
        } catch (Exception e) {
            e.printStackTrace();
            MONERO_WALLET_RPC_MANAGER.stopInstance(walletRpc, false);
            throw e;
        }
    }

    private MoneroWalletRpc startWalletRpcInstance(Integer port) {

        // check if monero-wallet-rpc exists
        if (!new File(MONERO_WALLET_RPC_PATH).exists()) throw new Error("monero-wallet-rpc executable doesn't exist at path " + MONERO_WALLET_RPC_PATH
                + "; copy monero-wallet-rpc to the project root or set WalletConfig.java MONERO_WALLET_RPC_PATH for your system");

        // get app's current daemon connection
        MoneroRpcConnection connection = connectionManager.getConnection();
        System.out.println("XmrWalletService.startWalletRpcInstance() connection from manager: " + connection);

        // start monero-wallet-rpc instance and return connected client
        List<String> cmd = new ArrayList<>(Arrays.asList( // modifiable list
                MONERO_WALLET_RPC_PATH, "--" + MONERO_NETWORK_TYPE.toString().toLowerCase(), "--daemon-address", connection.getUri(), "--rpc-login",
                MONERO_WALLET_RPC_USERNAME + ":" + MONERO_WALLET_RPC_PASSWORD, "--wallet-dir", walletDir.toString()));
        if (connection.getUsername() != null) {
            cmd.add("--daemon-login");
            cmd.add(connection.getUsername() + ":" + connection.getPassword());
        }
        if (port != null && port > 0) {
            cmd.add("--rpc-bind-port");
            cmd.add(Integer.toString(port));
        }
        return MONERO_WALLET_RPC_MANAGER.startInstance(cmd);
    }

    public void closeWallet(MoneroWallet walletRpc, boolean save) {
        MONERO_WALLET_RPC_MANAGER.stopInstance((MoneroWalletRpc) walletRpc, save);
    }

    public void deleteWallet(String walletName) {
        if (!walletExists(walletName)) throw new Error("Wallet does not exist at path: " + walletName);
        String path = walletDir.toString() + File.separator + walletName;
        if (!new File(path).delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".keys").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        if (!new File(path + ".address.txt").delete()) throw new RuntimeException("Failed to delete wallet file: " + path);
        // WalletsSetup.deleteRollingBackup(walletName); // TODO (woodser): necessary to
        // delete rolling backup?
    }

    // TODO (woodser): wallet has single password which is passed here?
    // TODO (woodser): test retaking failed trade. create new multisig wallet or
    // replace? cannot reuse

    public synchronized MoneroWallet createMultisigWallet(String tradeId) {
        if (multisigWallets.containsKey(tradeId)) return multisigWallets.get(tradeId);
        String path = "xmr_multisig_trade_" + tradeId;
        MoneroWallet multisigWallet = null;
        multisigWallet = createWallet(new MoneroWalletConfig().setPath(path).setPassword(accountService.getPassword()), null); // auto-assign port
        multisigWallets.put(tradeId, multisigWallet);
        multisigWallet.startSyncing(5000l);
        return multisigWallet;
    }

    public synchronized MoneroWallet getMultisigWallet(String tradeId) {
        if (multisigWallets.containsKey(tradeId)) return multisigWallets.get(tradeId);
        String path = "xmr_multisig_trade_" + tradeId;
        if (!walletExists(path)) return null;
        MoneroWallet multisigWallet = openWallet(new MoneroWalletConfig().setPath(path).setPassword(accountService.getPassword()), null);
        multisigWallets.put(tradeId, multisigWallet);
        multisigWallet.startSyncing(5000l); // TODO (woodser): use sync period from config. apps stall if too many multisig wallets and too short sync period
        return multisigWallet;
    }

    public synchronized boolean deleteMultisigWallet(String tradeId) {
        String walletName = "xmr_multisig_trade_" + tradeId;
        if (!walletExists(walletName)) return false;
        try {
            closeWallet(getMultisigWallet(tradeId), false);
        } catch (Exception err) {
            // multisig wallet may not be open
        }
        deleteWallet(walletName);
        multisigWallets.remove(tradeId);
        return true;
    }
    
    public void shutDown() {
        closeAllWallets();
    }
    
    private void closeAllWallets() {

        // collect wallets to shutdown
        List<MoneroWallet> openWallets = new ArrayList<MoneroWallet>();
        if (wallet != null) openWallets.add(wallet);
        for (String multisigWalletKey : multisigWallets.keySet()) {
            openWallets.add(multisigWallets.get(multisigWalletKey));
        }

        // create threads to close wallets
        List<Thread> threads = new ArrayList<Thread>();
        for (MoneroWallet openWallet : openWallets) {
            threads.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        closeWallet(openWallet, true);
                    } catch (Exception e) {
                        log.warn("Error closing monero-wallet-rpc subprocess. Was Haveno stopped manually with ctrl+c?");
                    }
                }
            }));
        }

        // run threads in parallel
        for (Thread thread : threads)
            thread.start();

        // wait for all threads
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        // clear wallets
        wallet = null;
        multisigWallets.clear();
    }
    
    // TODO (woodser): need trade manager to get trade ids to change all wallet passwords?
    public void setTradeManager(TradeManager tradeManager) {
        this.tradeManager = tradeManager;
    }
    
    private void changeWalletPasswords(String oldPassword, String newPassword) {
        System.out.println("XmrWalletService.changeWalletPasswords(" + oldPassword + ", " + newPassword);
        List<String> tradeIds = tradeManager.getTrades().stream().map(Trade::getId).collect(Collectors.toList());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(10, 1 + tradeIds.size()));
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    wallet.changePassword(oldPassword, newPassword);
                    wallet.save();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                }
            }
        });
        for (String tradeId : tradeIds) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    MoneroWallet multisigWallet = getMultisigWallet(tradeId); // TODO (woodser): this unnecessarily connects and syncs unopen wallets and leaves open
                    if (multisigWallet == null) return;
                    multisigWallet.changePassword(oldPassword, newPassword);
                    multisigWallet.save();
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60000, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            try { pool.shutdownNow(); }
            catch (Exception e2) { }
            throw new RuntimeException(e);
        }
    }
    
    public boolean isChainHeightSyncedWithinTolerance() {
        return connectionManager.isChainHeightSyncedWithinTolerance();
    }
    
    public XmrAddressEntry recoverAddressEntry(String offerId, String address, XmrAddressEntry.Context context) {
        var available = findAddressEntry(address, XmrAddressEntry.Context.AVAILABLE);
        if (!available.isPresent()) return null;
        return addressEntryList.swapAvailableToAddressEntryWithOfferId(available.get(), context, offerId);
    }

    public XmrAddressEntry getNewAddressEntry(String offerId, XmrAddressEntry.Context context) {
        MoneroSubaddress subaddress = wallet.createSubaddress(0);
        XmrAddressEntry entry = new XmrAddressEntry(subaddress.getIndex(), subaddress.getAddress(), context, offerId, null);
        addressEntryList.addAddressEntry(entry);
        return entry;
    }

    public XmrAddressEntry getOrCreateAddressEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        if (addressEntry.isPresent()) {
            return addressEntry.get();
        } else {
            // We try to use available and not yet used entries
            Optional<XmrAddressEntry> emptyAvailableAddressEntry = getAddressEntryListAsImmutableList().stream().filter(e -> XmrAddressEntry.Context.AVAILABLE == e.getContext())
                    .filter(e -> isSubaddressUnused(e.getSubaddressIndex())).findAny();
            if (emptyAvailableAddressEntry.isPresent()) {
                return addressEntryList.swapAvailableToAddressEntryWithOfferId(emptyAvailableAddressEntry.get(), context, offerId);
            } else {
                return getNewAddressEntry(offerId, context);
            }
        }
    }

    public Optional<XmrAddressEntry> getAddressEntry(String offerId, XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
    }

    public void swapTradeEntryToAvailableEntry(String offerId, XmrAddressEntry.Context context) {
        Optional<XmrAddressEntry> addressEntryOptional = getAddressEntryListAsImmutableList().stream().filter(e -> offerId.equals(e.getOfferId())).filter(e -> context == e.getContext()).findAny();
        addressEntryOptional.ifPresent(e -> {
            log.info("swap addressEntry with address {} and offerId {} from context {} to available", e.getAddressString(), e.getOfferId(), context);
            addressEntryList.swapToAvailable(e);
            saveAddressEntryList();
        });
    }

    public void resetAddressEntriesForOpenOffer(String offerId) {
        log.info("resetAddressEntriesForOpenOffer offerId={}", offerId);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.OFFER_FUNDING);
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.RESERVED_FOR_TRADE);
    }

    public void resetAddressEntriesForPendingTrade(String offerId) {
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.MULTI_SIG);
        // We swap also TRADE_PAYOUT to be sure all is cleaned up. There might be cases
        // where a user cannot send the funds
        // to an external wallet directly in the last step of the trade, but the funds
        // are in the Bisq wallet anyway and
        // the dealing with the external wallet is pure UI thing. The user can move the
        // funds to the wallet and then
        // send out the funds to the external wallet. As this cleanup is a rare
        // situation and most users do not use
        // the feature to send out the funds we prefer that strategy (if we keep the
        // address entry it might cause
        // complications in some edge cases after a SPV resync).
        swapTradeEntryToAvailableEntry(offerId, XmrAddressEntry.Context.TRADE_PAYOUT);
    }

    private Optional<XmrAddressEntry> findAddressEntry(String address, XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(e -> address.equals(e.getAddressString())).filter(e -> context == e.getContext()).findAny();
    }

    public List<XmrAddressEntry> getAvailableAddressEntries() {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> XmrAddressEntry.Context.AVAILABLE == addressEntry.getContext()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntriesForTrade() {
        return getAddressEntryListAsImmutableList().stream()
                .filter(addressEntry -> XmrAddressEntry.Context.MULTI_SIG == addressEntry.getContext() || XmrAddressEntry.Context.TRADE_PAYOUT == addressEntry.getContext())
                .collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntries(XmrAddressEntry.Context context) {
        return getAddressEntryListAsImmutableList().stream().filter(addressEntry -> context == addressEntry.getContext()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getFundedAvailableAddressEntries() {
        return getAvailableAddressEntries().stream().filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).isPositive()).collect(Collectors.toList());
    }

    public List<XmrAddressEntry> getAddressEntryListAsImmutableList() {
        return addressEntryList.getAddressEntriesAsListImmutable();
    }

    public boolean isSubaddressUnused(int subaddressIndex) {
        return subaddressIndex != 0 && getBalanceForSubaddress(subaddressIndex).value == 0;
        // return !wallet.getSubaddress(accountIndex, 0).isUsed(); // TODO: isUsed()
        // does not include unconfirmed funds
    }

    public Coin getBalanceForSubaddress(int subaddressIndex) {

        // get subaddress balance
        BigInteger balance = wallet.getBalance(0, subaddressIndex);

//    // balance from xmr wallet does not include unconfirmed funds, so add them  // TODO: support lower in stack?
//    for (MoneroTxWallet unconfirmedTx : wallet.getTxs(new MoneroTxQuery().setIsConfirmed(false))) {
//      for (MoneroTransfer transfer : unconfirmedTx.getTransfers()) {
//        if (transfer.getAccountIndex() == subaddressIndex) {
//          balance = transfer.isIncoming() ? balance.add(transfer.getAmount()) : balance.subtract(transfer.getAmount());
//        }
//      }
//    }

        System.out.println("Returning balance for subaddress " + subaddressIndex + ": " + balance.longValueExact());

        return Coin.valueOf(balance.longValueExact());
    }

    public Coin getAvailableConfirmedBalance() {
        return wallet != null ? Coin.valueOf(wallet.getUnlockedBalance(0).longValueExact()) : Coin.ZERO;
    }

    public Coin getSavingWalletBalance() {
        return wallet != null ? Coin.valueOf(wallet.getBalance(0).longValueExact()) : Coin.ZERO;
    }

    public Stream<XmrAddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<XmrAddressEntry> availableAndPayout = Stream.concat(getAddressEntries(XmrAddressEntry.Context.TRADE_PAYOUT).stream(), getFundedAvailableAddressEntries().stream());
        Stream<XmrAddressEntry> available = Stream.concat(availableAndPayout, getAddressEntries(XmrAddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, getAddressEntries(XmrAddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> getBalanceForSubaddress(addressEntry.getSubaddressIndex()).isPositive());
    }

    public void addBalanceListener(XmrBalanceListener listener) {
        balanceListeners.add(listener);
    }

    public void removeBalanceListener(XmrBalanceListener listener) {
        balanceListeners.remove(listener);
    }

    public void saveAddressEntryList() {
        addressEntryList.requestPersistence();
    }

    public List<MoneroTxWallet> getTransactions(boolean includeDead) {
        return wallet.getTxs(new MoneroTxQuery().setIsFailed(includeDead ? null : false));
    }
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Withdrawal Send
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String sendFunds(int fromAccountIndex, String toAddress, Coin receiverAmount, @SuppressWarnings("SameParameterValue") XmrAddressEntry.Context context,
            FutureCallback<MoneroTxWallet> callback) throws AddressFormatException, AddressEntryException, InsufficientMoneyException {
        try {
            MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig().setAccountIndex(fromAccountIndex).setAddress(toAddress).setAmount(ParsingUtils.coinToAtomicUnits(receiverAmount)).setRelay(true));
            callback.onSuccess(tx);
            printTxs("sendFunds", tx);
            return tx.getHash();
        } catch (Exception e) {
            callback.onFailure(e);
            throw e;
        }
    }

//  public String sendFunds(String fromAddress, String toAddress, Coin receiverAmount, Coin fee, @Nullable KeyParameter aesKey, @SuppressWarnings("SameParameterValue") AddressEntry.Context context,
//      FutureCallback<Transaction> callback) throws AddressFormatException, AddressEntryException, InsufficientMoneyException {
//    SendRequest sendRequest = getSendRequest(fromAddress, toAddress, receiverAmount, fee, aesKey, context);
//    Wallet.SendResult sendResult = wallet.sendCoins(sendRequest);
//    Futures.addCallback(sendResult.broadcastComplete, callback, MoreExecutors.directExecutor());
//
//    printTx("sendFunds", sendResult.tx);
//    return sendResult.tx.getTxId().toString();
//  }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Create Tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MoneroTxWallet createTx(List<MoneroDestination> destinations) {
        try {
            MoneroTxWallet tx = wallet.createTx(new MoneroTxConfig().setAccountIndex(0).setDestinations(destinations).setRelay(false).setCanSplit(false));
            printTxs("XmrWalletService.createTx", tx);
            return tx;
        } catch (Exception e) {
            throw e;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Util
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static void printTxs(String tracePrefix, MoneroTxWallet... txs) {
        StringBuilder sb = new StringBuilder();
        for (MoneroTxWallet tx : txs) sb.append('\n' + tx.toString());
        log.info("\n" + tracePrefix + ":" + sb.toString());
    }

    private void notifyBalanceListeners() {
        for (XmrBalanceListener balanceListener : balanceListeners) {
            Coin balance;
            if (balanceListener.getSubaddressIndex() != null && balanceListener.getSubaddressIndex() != 0) balance = getBalanceForSubaddress(balanceListener.getSubaddressIndex());
            else balance = getAvailableConfirmedBalance();
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    balanceListener.onBalanceChanged(BigInteger.valueOf(balance.value));
                }
            });
        }
    }

    private void setWalletDaemonConnections(MoneroRpcConnection connection) {
        log.info("Setting wallet daemon connections: " + (connection == null ? null : connection.getUri()));
        if (wallet != null) wallet.setDaemonConnection(connection);
        for (MoneroWallet multisigWallet : multisigWallets.values()) multisigWallet.setDaemonConnection(connection);
    }

    /**
     * Wraps a MoneroWalletListener to notify the Haveno application.
     *
     * TODO (woodser): this is no longer necessary since not syncing to thread?
     */
    public class HavenoWalletListener extends MoneroWalletListener {

        private MoneroWalletListener listener;

        public HavenoWalletListener(MoneroWalletListener listener) {
            this.listener = listener;
        }

        @Override
        public void onSyncProgress(long height, long startHeight, long endHeight, double percentDone, String message) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onSyncProgress(height, startHeight, endHeight, percentDone, message);
                }
            });
        }

        @Override
        public void onNewBlock(long height) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onNewBlock(height);
                }
            });
        }

        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onBalancesChanged(newBalance, newUnlockedBalance);
                }
            });
        }

        @Override
        public void onOutputReceived(MoneroOutputWallet output) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onOutputReceived(output);
                }
            });
        }

        @Override
        public void onOutputSpent(MoneroOutputWallet output) {
            UserThread.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onOutputSpent(output);
                }
            });
        }
    }
}
