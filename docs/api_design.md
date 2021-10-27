# API Design

## Add API functions to initialize Haveno account

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to create or restore a Haveno account on startup.

These API calls will be used to feed Haveno's startup UI: [screenshots]

The following API functions are requested additions to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| `havenod.accountExists()` | boolean | Indicates if the Haveno account is created.
| `havenod.isAccountOpen()` | boolean | Indicates if the Haveno account is open and authenticated with the correct password.
| `havenod.createAccount(password: string)` | void | Create and open a new Haveno account. Throw error if `accountExists()`.
| `havenod.openAccount(password: string)` | void | Open existing account. Throw error if `!accountExists() || isAccountOpen()`.
| `havenod.closeAccount()` | void | Close the currently open account. Throw error if `!isAccountOpen()`.
| `havenod.backupAccount()` | zip bytes for download | Backup the account to a zip file. Throw error if `!accountExists()`.
| `havenod.deleteAccount()` | void | Permanently delete the Haveno account.
| `havenod.restoreAccount(zip: zip)` | void | Restore the account from a zip file. Throw error if `accountExists()`.
| `havenod.changePassword(password: string)` | void | Change the Haveno account password. Throw error if `!isAccountOpen()`.

### How to implement

Currently, `./haveno-daemon` automatically initializes an account folder with the name provided at startup using the `--appName` argument.

Instead, `./haveno-daemon` should start its gRPC API and wait for `createAccount()` or `restoreAccount()` to be called to initialize the account folder.

These API calls should be added as a new service, e.g. `HavenoAccount`, in Haveno's [protobuf definition](https://github.com/haveno-dex/haveno/blob/master/proto/src/main/proto/grpc.proto).

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test new API functions end-to-end.

## Add API functions to start and stop local Monero node

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to start and stop a local Monero node.

The following functions are requested as additions to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| `havenod.startMoneroNode(rpcUsername: string, rpcPassword: string)` | `void` | Start local monerod process, throw error if fails.
| `havenod.stopMoneroNode()` | `void` | Stop local monerod process, throw error if fails.

### How to implement

A new constructor should be added to [MoneroDaemonRpc.java](https://github.com/monero-ecosystem/monero-java/blob/master/src/main/java/monero/daemon/MoneroDaemonRpc.java) in nearly the identical manner as [MoneroWalletRpc.java](https://github.com/monero-ecosystem/monero-java/blob/48c5bf0004759669afe539e9c675a77a2eb10391/src/main/java/monero/wallet/MoneroWalletRpc.java#L134) to do the same task of starting the executable.

In addition, a new test should be added to [TestMoneroDaemonRpc.java](https://github.com/monero-ecosystem/monero-java/blob/master/src/test/java/test/TestMoneroDaemonRpc.java) to test the new constructor by starting a node, interacting with it, and stopping it.

Refer to [how monero-wallet-rpc instances are started and stopped](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/core/src/main/java/bisq/core/btc/setup/WalletConfig.java#L322) in Haveno for reference as this pattern will be similar.

## Add API functions to manage Monero daemon connections

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to manage Monero daemon connections.

The following API functions are requested additions to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| havenod.getMoneroConnection() | UrlConnection | Get current connection.
| havenod.getMoneroConnection(url: string) | UrlConnection | Get connection with the given url.
| havenod.getMoneroConnections() | UrlConnection[] | Get all managed connections, never returns their login credentials
| havenod.checkMoneroConnection() | UrlConnection | Check status of current connection
| havenod.checkMoneroConnection(connection) | UrlConnection | Check status of given connection.
| havenod.checkMoneroConnections() | UrlConnection[] | Check status of all managed connections
| havenod.setMoneroConnection(url: string) | void | Set current connection based on current state.
| havenod.setMoneroConnection(connection: UrlConnection) | void | Set current connection, overwrite login credentials.
| havenod.addMoneroConnection(connection: UrlConnection) | void | Add managed connection.
| havenod.removeMoneroConnection(url: string) | void | Remove managed connection.
| havenod.connect() | void | Automatically set current connection to best available connection and check connection.
| havenod.connect(url: string) | void | Connect to connection with given url, throw if not connected.
| havenod.connect(connection: UrlConnection) | void | Update and connect to connection, throw if not connected.
| havenod.startAutoRefresh(refreshPeriod: number) | void | Automatically refresh the connection status by polling the server in a fixed period loop.
| havenod.stopAutoRefresh() | void | Stop automatically refreshing the connection status.
| havenod.setAutoSwitch(autoSwitch: boolean) | void | Automatically switch to best available connection if current connection disconnects.

```javascript
UrlConnection
url: string
username: string
password: string
priority: int // the higher the number, the higher the relative priority
isOnline: boolean // whether or not the daemon is online
isAuthenticated: boolean // null if no authentication, true if authenticated, false if not authenticated
```

The following untested pseudocode demonstrates how the API can be used in the UI:

```javascript
// get haveno's preset localhost connection
let localConnection;
for (let connection of await havenod.getMoneroConnections()) {
  if (connection.getUrl().contains("localhost")) {
    localConnection = connection;
    break;
  }
}
if (!localConnection) throw new Error("Haveno expected to have preset localhost");

// connect to localhost if available
localConnectionStatus = await havenod.checkMoneroConnection(localConnection); // check localhost connection status
if (localConnectionStatus.isOnline()) {
  if (!localConnectionStatus.isAuthenticated()) {
    localConnection.setCredentials("superuser", "abctesting123"); // set credentials if needed
    await havenod.setMoneroConnection(localConnection); // overwrite connection credentials
  } else {
    await havenod.setMoneroConnection(localConnection.getUrl()); // connect without changing login credentials
  }
}

// select among connections
else {
  let connections: UrlConnection[] = await havenod.checkMoneroConnections();
  let selectedConnection = connections[0]; // selectedConnection.isOnline() === true
  if (selectedConnection.isAuthenticated() === false) {
    selectedConnection.setCredentials("superuser", "abctesting123");
    await havenod.connect(selectedConnection);
  } else {
    await havenod.setMoneroConnection(selectedConnection);
  }
  
  // verify connection
  let connectionStatus = await havenod.checkMoneroConnection();
  assert(connectionStatus.getUrl() === selectedConnection.getUrl() && connectionStatus.isOnline() && connectionStatus.isAuthenticated() !== false);
}
```

### How to implement

These new API functions should be added as a new service, e.g. `MoneroConnections`, in [Haveno's protobuf definition](https://github.com/haveno-dex/haveno/blob/master/proto/src/main/proto/grpc.proto).

It needs to be decided how to encrypt daemon connection credentials.

These API functions should be implemented using monero-java's [MoneroConnectionManager](https://moneroecosystem.org/monero-java/monero/common/MoneroConnectionManager.html) on the backend. This class should likely be updated in monero-java for better consistency and support in Haveno.

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test new API functions end-to-end.

## Add API function to get market prices for all trade pairs

This issue requests [adding a new API function](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to get market prices of all trade pairs.

The following API function is a requested addition to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| `havenod.getMarketPrices()` | `MarketPrice[]` | Get market price data for all trade pairs.

This function returns an array of `MarketPrice` types, each with the following attributes:

```javascript
MarketPrice
currencyCode: string
price: double
24HourChange: double
24HourVolume: double
isFiatCurrency: boolean // to know if trade pair label is inverted (e.g. "XMR/USD" vs "ETH/XMR")
```

### How to implement

This new API function should be added to the [`Price` service](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/proto/src/main/proto/grpc.proto#L238) of Haveno's protobuf definition, to be named `GetMarketPrices`.

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test the new API function end-to-end.

## Add API function to get market depth data of a trade pair

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to get market depth data of a trade pair.

The returned data will feed market depth graphs in the UI: [screenshot]

The following function is a requested addition to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| `havenod.getMarketPrices()` | `MarketDepth` | Get market depth data for a trade pair

The following type is returned:

```javascript
MarketDepth
currencyCode: string
buyPrices: double[]
buyDepth: BigInt[]
sellPrices: double[]
sellDepth: BigInt[]
```

### How to implement

This new API function should be added to the [`Price` service](https://github.com/haveno-dex/haveno/blob/b761dbfd378faf49d95090c126318b419af7926b/proto/src/main/proto/grpc.proto#L238) of Haveno's protobuf definition, to be named `GetMarketDepth`.

It should return the same data points as currently used in the desktop app.

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test the new API function end-to-end.

## Add API functions to get wallet transfers and withdraw funds

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to get transfers and withdraw funds.

The following functions are requested as additions to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx). Feedback is welcome.

| API Function | Return | Description
| --- | --- | --- |
| `havenod.getXmrTxs()` | `XmrTx[]` | Get all transactions with transfers from or to Haveno's Monero wallet.
| `havenod.createXmrTx(destinations: XmrDestination)` | `XmrTx` | Create but do not relay a transaction to send funds from Haveno's Monero wallet.
| `havenod.relayXmrTx()` | `string` | Relay a previously created transaction.

```javascript
MoneroTx
hash: string
timestamp: timestamp
transfers: XmrTransfer[]
metadata: string // used to later relay the transaction
```

```javascript
XmrTransfer
address: string
amount: BigInt // as string in gRPC
isIncoming: boolean
destinations: XmrDestination[] // only present on outgoing transfers from local wallet data
```

```javascript
XmrDestination
address: string
amount: BigInt // as string in gRPC
```

Note these wallet functions are already implemented:

| API Function | Return | Description
| --- | --- | --- |
| `havenod.getNewDepositSubaddress()` | `string` | Get a new subaddress in the Haveno wallet to receive deposits.
| `havenod.getBalances()` | `XmrBalanceInfo` | Get the user's Monero balances.

### How to implement

A test should be added to [HavenoDaemon.test.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.test.tsx) to test all Haveno wallet functions including sending and receiving funds and testing wallet balances and transactions.

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test new API functions end-to-end.

## Add API functions to support trade chat

This issue requests [adding new API functions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to support trade chats.

The following functions are requested as additions to [HavenoDaemon.tsx](https://github.com/haveno-dex/haveno-ui-poc/blob/master/src/HavenoDaemon.tsx) (feedback welcome).

| API Function | Return | Description
| --- | --- | --- |
| `havenod.getAllChatMessages(tradeId: string)` | `ChatMessage[]` | Get all chat messages for a trade.
| `havenod.onChatMessage(listener: function` | `void` | Invokes the given function when a new chat message arrives, i.e. `await havenod.onChatMessage(function(message: ChatMessage) { ... });`

### How to implement

We want the client to receive push notifications from the gRPC backend when new chat messages arrive.

We intend to use gRPC streaming and/or websockets to achieve push notifications. Here is some documentation to get started:

- [Streaming example using gRPC web](https://github.com/grpc/grpc-web#advanced-demo-browser-echo-app)
- [Server-side streaming example](https://github.com/grpc/grpc-web#3-write-your-js-client)
- [Using websockets with gRPC](https://github.com/improbable-eng/grpc-web/issues/94#issuecomment-386004116)

Follow [these instructions](https://github.com/haveno-dex/haveno-ui-poc/blob/master/docs/adding_api_functions.md) to add and test new API functions end-to-end.
