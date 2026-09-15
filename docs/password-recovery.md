# Recovering an interrupted password change

Haveno validates the new password, updates all wallet and stored connection passwords, then atomically replaces the account key wrapper. Retained trade wallets are updated without restarting their trades. Password changes require account initialization and use the existing wallet locks and native or RPC synchronization while updating wallets.

If an unexpected error interrupts the change, Haveno blocks further password changes and password-dependent wallet operations. It does not roll back or retry the change. Keep both passwords, close Haveno, and use the recovery tool. A crash can also leave files using different passwords.

## Recovery in the application

On the login screen, choose **Help** in the bottom-left corner, then **Open password repair...**. You can also choose **Repair a password change...** under **Account > Wallet password**, after an account startup failure, or after a wallet password error during startup.

Haveno closes and opens a separate recovery screen for the same profile. Wait for shutdown to finish, then use **Open directory** to make a complete copy of the application data folder before proceeding. Close any other Haveno or wallet applications using this data.

Enter the **current account password** and the **previous or attempted password** from the failed change. Leave the current field empty if the account has no password. Unset passwords are always tried automatically, including the internal wallet default `password`; there is no need to type that default. Use **Add another password** if several changes failed with different passwords.

Select **Repair wallets** after confirming the backup. Recovery runs offline, without trading or synchronization. Keep the window open until it finishes. On success, close recovery and start Haveno normally. If it fails, **Try again** opens a fresh recovery screen; re-enter all relevant passwords. If the current account password was rejected, try the other password in the first field. Unknown passwords cannot be recovered or bypassed.

The screen is also available from the desktop launcher with `--recover-password` followed by the network data directory. For example, with an updated macOS installation:

```sh
/Applications/Haveno.app/Contents/MacOS/Haveno --recover-password '/path/to/application-data/xmr_mainnet'
```

This uses the bundled runtime; a separate Java installation or source checkout is unnecessary. Other platforms use their installed Haveno executable with the same arguments. Older installations must first be updated to a version containing this screen. For source builds, use the generated desktop launcher, or `java -cp desktop/build/libs/desktop-1.8.0-SNAPSHOT-all.jar haveno.desktop.app.HavenoAppMain --recover-password '/path/to/application-data/xmr_mainnet'` with the filename produced by your build. Close Haveno before starting recovery manually.

## Terminal recovery

Close Haveno and all its wallet processes, and back up the complete application data directory. From a built Haveno source checkout, run:

```sh
java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword '/path/to/application-data/xmr_mainnet'
```

Build the jar with `./gradlew :daemon:shadowJar` if needed. Use `xmr_local` for local/testnet or `xmr_stagenet` for stagenet. The tool also accepts `xmr_testnet`. The directory must contain `keys` and `wallet`.

Enter the current account password at the hidden prompt, leaving it empty if unset. Then enter the other password from the interrupted change; finish with an empty entry. If earlier changes used additional passwords, enter those too. Unset passwords are tried automatically. Passwords are never command arguments or written to a recovery file.

If the current account password is rejected, try the other password at the first prompt. The password that opens the account key wrapper is authoritative. Recovery keeps that account password and master key unchanged, and repairs the wallets and stored connection credentials to match them.

The tool operates offline, including retained trade wallets, orphan wallets and interrupted seed restores. It does not synchronize wallets or start trading. Native wallets are used when available; otherwise it uses the installed `monero-wallet-rpc`. To choose RPC explicitly, append the executable path as the second argument.

A failure stops the tool. After resolving the error, rerun it with all relevant passwords; already repaired wallets can be processed again. Unknown passwords cannot be recovered or bypassed. If the account key wrapper cannot open with either password, restore a complete readable backup before recovery.

Start Haveno normally after recovery succeeds. Changes involving many retained wallets can take several minutes.

## Backups

If `db/EncryptedConnectionList` was quarantined, restore a readable copy from the same account before recovery. Rolling copies are in `db/backup/backups_EncryptedConnectionList/`. Preserve `db/backup_of_corrupted_data/EncryptedConnectionList` and your complete directory backup until recovery succeeds.

After a successful password change or repair, Haveno removes automatic wallet backups because they may still open with a previous password or the internal default. Account-key backups are removed only when they contain the same master key under a superseded password; foreign or unreadable account-key backups are preserved. Copies outside the application directory are unaffected.

If backup cleanup fails after the change, Haveno reports that the password changed successfully and that older backups may remain accessible with a previous or unset password. Use the new password. This warning does not block the account or require password repair; rerunning recovery with the relevant passwords also retries backup cleanup.

Account exports cannot interleave with a password change and are blocked after an unsuccessful change. Close Haveno and copy the complete application data directory to preserve an interrupted state for recovery.
