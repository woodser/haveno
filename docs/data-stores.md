# Data stores

### Update stores

With every release we include the latest snapshot of Mainnet and Testnet data from the P2P network within the client.

* Start your Haveno client on Mainnet and Testnet and let it run until it is fully synced.
* Run [copy_dbs.sh](https://github.com/bisq-network/bisq/blob/master/desktop/package/macosx/copy_dbs.sh) to copy the
required files into the [p2p resources directory](https://github.com/bisq-network/bisq/blob/master/p2p/src/main/resources).
* To add a new trade statistic snapshot just add it to the list of trade statistic snapshots in https://github.com/bisq-network/bisq/blob/0345c795e2c227d827a1f239a323dda1250f4e69/common/src/main/java/haveno/common/app/Version.java#L40

## Authenticated trade statistics

Updated arbitrators attest eligible completed trades using their existing signature key.
The `SignedTradeStatistics` record includes the arbitrator registration certificate,
so verification does not depend on a live arbitrator announcement. Receivers verify
the network, format version, registration root, certificate, and statistics signature
before storing or relaying the record. Disk loading repeats verification. Network
operators must configure `ArbitratorManager.getRegistrationPubKeys()`; upstream's
mainnet list is empty and never falls back to development keys.

The signature covers the complete statistics, including the arbitrator and price
interpretation metadata, with deterministic serialization and a separate signing
domain. It is bound to the Monero network and `Version.P2P_NETWORK_VERSION`. Changing
that network identifier requires an explicit migration of authenticated history.
The storage hash excludes the randomized signatures, so resigning the same content
cannot create additional statistics. No trade IDs, transaction IDs, exact amounts,
or exact dates are added to the already published statistics.

`SignedTradeStatisticsStore` is separate from the legacy store. An unsigned copy
cannot occupy the signed record's storage key. The manager combines records by
legacy statistics hash, preferring verified content regardless of arrival order.
If several authorized attestations exist for a hash, the lowest unsigned bytewise
content hash wins deterministically. The UI and JSON statistics retain their existing
format and history. RPC callers can request proofs with
`include_signed_trade_statistics`; the default reply size remains unchanged. Legacy records are not thereby authenticated.

Update seed nodes first, then arbitrators and clients. The new capability prevents
sending signed records to older peers. Arbitrators continue publishing legacy records
and independently backfill signatures for eligible retained trades, even when the
legacy statistics already exist. Stored attestations reach later upgraded peers through
normal data synchronization without repeated broadcasts. Existing publication delays
and data fuzzing remain. Each attestation carries its own public key and certificate,
which increases storage and sync traffic; bootstrap responses remain paginated.
The old publication variants are checked to avoid introducing another copy of a trade.
Only retained trades meeting the existing publication criteria can be backfilled.
Attesting early history rebuilds its legacy deduplication index so verified records
take precedence over conflicting legacy records.

This release does not require a mandatory update and does not reject unsigned history.
It therefore does not yet prevent fabricated unsigned records from contributing to
statistics during the transition. Authentication means an authorized arbitrator
attested the record; it does not independently prove fiat payment or prevent wash
trading or a dishonest authorized arbitrator.

A later enforcement release must bundle an agreed legacy snapshot and accept only
that snapshot's canonical records plus verified records. Do not use a sender-reported
date cutoff or trust whatever a client happened to receive before activation: both
permit fabricated history. Legacy hashes exclude interpretation metadata, so snapshot
membership must restore the snapshot's complete record rather than trust a network
record merely sharing its hash. Deploy the snapshot and enforcement policy together
and coordinate the required network update. No activation date or snapshot is selected
by this change.

Removing a registration root revokes its attestations on the next load. Existing onion
address bans govern live arbitrator selection; they are not revocation certificates
for historical statistics. Preserve any approved legacy history through the agreed
snapshot when retiring registration roots.
