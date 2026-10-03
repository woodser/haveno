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
import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.app.Capability;
import haveno.common.crypto.CryptoException;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.core.trade.Trade;
import haveno.core.trade.statistics.SignedTradeStatistics;
import haveno.core.trade.statistics.SignedTradeStatisticsStore;
import haveno.core.trade.statistics.SignedTradeStatisticsStorageService;
import haveno.core.trade.statistics.TradeStatistics3;
import haveno.core.trade.statistics.TradeStatistics3StorageService;
import haveno.core.trade.statistics.TradeStatisticsManager;
import haveno.core.user.User;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.P2PService;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.payload.InvalidPersistableNetworkPayloadException;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreListener;
import haveno.network.p2p.storage.persistence.AppendOnlyDataStoreService;
import javafx.collections.ListChangeListener;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;
import org.mockito.MockedStatic;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.KeyStorage;
import haveno.common.crypto.Sig;
import haveno.common.file.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SigTest {
    private static final Logger log = LoggerFactory.getLogger(SigTest.class);
    private KeyRing keyRing;
    private File dir;
    private ECKey registrationKey;
    private Arbitrator arbitrator;

    @BeforeEach
    public void setup() throws IOException {

        dir = File.createTempFile("temp_tests", "");
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdir();
        KeyStorage keyStorage = new KeyStorage(dir);
        keyRing = new KeyRing(keyStorage, null, true);
        registrationKey = new ECKey();
        arbitrator = new Arbitrator(new NodeAddress("arbitrator:9999"), keyRing.getPubKeyRing(), List.of("en"), 0,
                registrationKey.getPubKey(), registrationKey.signMessage(Utils.HEX.encode(keyRing.getPubKeyRing().getSignaturePubKeyBytes())),
                null, null, null);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtil.deleteDirectory(dir);
    }


    @Test
    public void testSignature() {
        long ts = System.currentTimeMillis();
        log.trace("start ");
        for (int i = 0; i < 100; i++) {
            String msg = String.valueOf(new Random().nextInt());
            String sig = null;
            try {
                sig = Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), msg);
            } catch (CryptoException e) {
                log.error("sign failed");
                e.printStackTrace();
                assertTrue(false);
            }
            try {
                assertTrue(Sig.verify(keyRing.getSignatureKeyPair().getPublic(), msg, sig));
            } catch (CryptoException e) {
                log.error("verify failed");
                e.printStackTrace();
                assertTrue(false);
            }
        }
        log.trace("took {} ms.", System.currentTimeMillis() - ts);
    }

    private TradeStatistics3 statistics() {
        return new TradeStatistics3("BTC", 200000000L, 1000000000000L, "BLOCK_CHAINS", 1750000000000L,
                "arbi", Map.of("v", "3"));
    }

    private MockedStatic<ArbitratorManager> authorizeSigner() {
        MockedStatic<ArbitratorManager> roots = mockStatic(ArbitratorManager.class);
        roots.when(ArbitratorManager::getRegistrationPubKeys).thenReturn(List.of(Utils.HEX.encode(registrationKey.getPubKey())));
        return roots;
    }

    @Test
    public void signedStatisticsRoundTripAndRejectTampering() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner()) {
            SignedTradeStatistics signed = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            protobuf.SignedTradeStatistics proto = signed.toProtoSignedTradeStatistics();
            assertEquals(statistics().toProtoTradeStatistics3(), SignedTradeStatistics.fromProto(proto).getTradeStatistics().toProtoTradeStatistics3());
            assertTrue(signed.getRequiredCapabilities().containsAll(Capability.SIGNED_TRADE_STATISTICS));
            TradeStatistics3 copy = signed.getTradeStatistics();
            copy.pruneOptionalData();
            copy.getExtraDataMap().clear();
            copy.getHash()[0] ^= 1;
            copy.getDate().setTime(0);
            assertEquals(proto.getTradeStatistics(), signed.getTradeStatistics().toProtoTradeStatistics3());
            for (protobuf.SignedTradeStatistics invalid : List.of(
                    proto.toBuilder().setTradeStatistics(proto.getTradeStatistics().toBuilder().setArbitrator("fake")).build(),
                    proto.toBuilder().setTradeStatistics(proto.getTradeStatistics().toBuilder().clearExtraData()).build(),
                    proto.toBuilder().setNetwork("OTHER_NETWORK").build(),
                    proto.toBuilder().setVersion(2).build(),
                    proto.toBuilder().setSignature(ByteString.copyFrom(new byte[64])).build(),
                    proto.toBuilder().setRegistrationSignature("invalid").build(),
                    proto.toBuilder().setArbitratorPubKey(ByteString.copyFrom(new byte[1025])).build(),
                    proto.toBuilder().clearSignature().build())) {
                assertThrows(InvalidPersistableNetworkPayloadException.class, () -> SignedTradeStatistics.fromProto(invalid));
            }
            TradeStatistics3 changed = new TradeStatistics3("BTC", 300000000L, 1000000000000L, "BLOCK_CHAINS", 1750000000000L, "arbi", Map.of("v", "3"));
            assertThrows(InvalidPersistableNetworkPayloadException.class, () -> SignedTradeStatistics.fromProto(
                    proto.toBuilder().setTradeStatistics(changed.toProtoTradeStatistics3()).build()));

            // Authorization is checked again even when the certificate was previously cached.
            roots.when(ArbitratorManager::getRegistrationPubKeys).thenReturn(List.of());
            assertThrows(InvalidPersistableNetworkPayloadException.class, () -> SignedTradeStatistics.fromProto(proto));
        }
    }

    @Test
    public void signedStatisticsHashIgnoresSignatureRandomnessAndMapOrder() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner()) {
            SignedTradeStatistics first = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            SignedTradeStatistics repeated = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            assertArrayEquals(first.getHash(), repeated.getHash());
            Map<String, String> one = new LinkedHashMap<>();
            one.put("v", "3");
            one.put("test", "value");
            Map<String, String> two = new LinkedHashMap<>();
            two.put("test", "value");
            two.put("v", "3");
            TradeStatistics3 a = new TradeStatistics3("BTC", 200000000L, 1000000000000L, "BLOCK_CHAINS", 1750000000000L, "arbi", one);
            TradeStatistics3 b = new TradeStatistics3("BTC", 200000000L, 1000000000000L, "BLOCK_CHAINS", 1750000000000L, "arbi", two);
            assertArrayEquals(SignedTradeStatistics.sign(a, arbitrator, keyRing).getHash(), SignedTradeStatistics.sign(b, arbitrator, keyRing).getHash());
        }
    }

    @Test
    public void signedStatisticsStoreReverifiesEveryRecord() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner()) {
            SignedTradeStatistics signed = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            protobuf.SignedTradeStatisticsStore records = protobuf.SignedTradeStatisticsStore.newBuilder()
                    .addItems(signed.toProtoSignedTradeStatistics())
                    .addItems(signed.toProtoSignedTradeStatistics().toBuilder().clearSignature())
                    .build();
            SignedTradeStatisticsStore store = SignedTradeStatisticsStore.fromProto(records);
            assertEquals(1, store.getMap().size());
            assertTrue(store.containsKey(new P2PDataStorage.ByteArray(signed.getHash())));
            roots.when(ArbitratorManager::getRegistrationPubKeys).thenReturn(List.of());
            assertEquals(0, SignedTradeStatisticsStore.fromProto(records).getMap().size());
        }
    }

    @Test
    public void verifiedStatisticsOverrideUnsignedMetadataInEitherArrivalOrder() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner(); MockedStatic<UserThread> thread = mockStatic(UserThread.class)) {
            SignedTradeStatistics signed = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            TradeStatistics3 unsigned = TradeStatistics3.fromProto(statistics().toProtoTradeStatistics3().toBuilder()
                    .clearExtraData().setArbitrator("fake").build());
            AtomicReference<Runnable> flush = new AtomicReference<>();
            thread.when(() -> UserThread.runAfter(any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
                flush.set(invocation.getArgument(0));
                return mock(Timer.class);
            });
            for (List<PersistableNetworkPayload> order : List.of(List.<PersistableNetworkPayload>of(unsigned, signed),
                    List.<PersistableNetworkPayload>of(signed, unsigned))) {
                StatisticsFixture fixture = new StatisticsFixture();
                fixture.manager.onAllServicesInitialized();
                for (PersistableNetworkPayload payload : order) {
                    fixture.listener.get().onAdded(payload);
                    flush.get().run();
                }
                assertEquals(1, fixture.manager.getTradeStatisticsListCopy().size());
                assertEquals(statistics().toProtoTradeStatistics3(), fixture.manager.getTradeStatisticsListCopy().get(0).toProtoTradeStatistics3());
                assertEquals(1, fixture.manager.getSignedTradeStatisticsListCopy().size());
                fixture.manager.shutDown();
            }
        }
    }

    @Test
    public void verifiedStatisticsReplaceLegacyRecordsInBatches() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner(); MockedStatic<UserThread> thread = mockStatic(UserThread.class)) {
            AtomicReference<Runnable> flush = new AtomicReference<>();
            thread.when(() -> UserThread.runAfter(any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
                flush.set(invocation.getArgument(0));
                return mock(Timer.class);
            });
            StatisticsFixture fixture = new StatisticsFixture();
            List<SignedTradeStatistics> signed = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                TradeStatistics3 statistics = new TradeStatistics3("BTC", 200000000L, 1000000000000L,
                        "BLOCK_CHAINS", 1750000000000L + i, "arbi", Map.of("v", "3"));
                TradeStatistics3 unsigned = TradeStatistics3.fromProto(statistics.toProtoTradeStatistics3().toBuilder().clearExtraData().build());
                fixture.legacy.put(new P2PDataStorage.ByteArray(unsigned.getHash()), unsigned);
                signed.add(SignedTradeStatistics.sign(statistics, arbitrator, keyRing));
            }
            fixture.manager.onAllServicesInitialized();
            AtomicInteger changes = new AtomicInteger();
            fixture.manager.getObservableTradeStatisticsList().addListener((ListChangeListener<TradeStatistics3>) change -> changes.incrementAndGet());
            signed.forEach(statistics -> {
                fixture.listener.get().onAdded(statistics);
                fixture.listener.get().onAdded(statistics.getTradeStatistics());
            });
            flush.get().run();
            assertEquals(100, fixture.manager.getTradeStatisticsListCopy().size());
            assertTrue(changes.get() <= 2);
            assertTrue(fixture.manager.getTradeStatisticsListCopy().stream().allMatch(statistics -> "3".equals(statistics.getExtraDataMap().get("v"))));
        }
    }

    @Test
    public void verifiedStatisticsOverrideUnsignedMetadataAfterRestart() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner()) {
            SignedTradeStatistics signed = SignedTradeStatistics.sign(statistics(), arbitrator, keyRing);
            TradeStatistics3 unsigned = TradeStatistics3.fromProto(statistics().toProtoTradeStatistics3().toBuilder().clearExtraData().build());
            StatisticsFixture fixture = new StatisticsFixture();
            fixture.legacy.put(new P2PDataStorage.ByteArray(unsigned.getHash()), unsigned);
            fixture.signed.put(new P2PDataStorage.ByteArray(signed.getHash()), signed);
            fixture.manager.onAllServicesInitialized();
            assertEquals(1, fixture.manager.getTradeStatisticsListCopy().size());
            assertEquals(statistics().toProtoTradeStatistics3(), fixture.manager.getTradeStatisticsListCopy().get(0).toProtoTradeStatistics3());
        }
    }

    @Test
    public void arbitratorBackfillsSignatureWhenLegacyStatisticAlreadyExists() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner(); MockedStatic<UserThread> thread = mockStatic(UserThread.class);
             MockedStatic<TradeStatistics3> statisticsFactory = mockStatic(TradeStatistics3.class, CALLS_REAL_METHODS);
             MockedStatic<SignedTradeStatistics> signing = mockStatic(SignedTradeStatistics.class, CALLS_REAL_METHODS)) {
            Trade trade = mock(Trade.class);
            when(trade.shouldPublishTradeStatistics()).thenReturn(true);
            when(trade.isArbitrator()).thenReturn(true);
            TradeStatistics3 statistics = statistics();
            statisticsFactory.when(() -> TradeStatistics3.fromV0(trade, null, true)).thenReturn(statistics);
            statisticsFactory.when(() -> TradeStatistics3.fromV1(trade, null, true)).thenReturn(statistics);
            statisticsFactory.when(() -> TradeStatistics3.fromV2(trade, null, true)).thenReturn(statistics);
            StatisticsFixture fixture = new StatisticsFixture();
            fixture.legacy.put(new P2PDataStorage.ByteArray(statistics.getHash()), statistics);
            List<Runnable> publications = new ArrayList<>();
            thread.when(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        publications.add(invocation.getArgument(0));
                        return mock(Timer.class);
                    });
            fixture.manager.onAllServicesInitialized();
            fixture.manager.maybePublishTradeStatistics(trade, null, true);
            fixture.manager.maybePublishTradeStatistics(trade, null, true);
            assertEquals(1, publications.size());
            signing.verify(() -> SignedTradeStatistics.sign(any(), any(), any()), never());
            publications.forEach(Runnable::run);
            signing.verify(() -> SignedTradeStatistics.sign(statistics, arbitrator, keyRing));
            verify(fixture.p2p).addPersistableNetworkPayload(any(SignedTradeStatistics.class), eq(false));
            verify(fixture.p2p, never()).addPersistableNetworkPayload(any(TradeStatistics3.class), eq(true));
        }
    }

    @Test
    public void verifiedEarlyHistoryDisplacesConflictingUnsignedHistory() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner(); MockedStatic<UserThread> thread = mockStatic(UserThread.class)) {
            TradeStatistics3 early = new TradeStatistics3("BTC", 200000000L, 1000000000000L, "BLOCK_CHAINS", 1700000000000L, "arbi", Map.of("v", "3"));
            TradeStatistics3 conflicting = new TradeStatistics3("BTC", 900000000L, 2000000000000L, "BLOCK_CHAINS", 1700000000000L, "fake", Map.of("v", "3"));
            SignedTradeStatistics signed = SignedTradeStatistics.sign(early, arbitrator, keyRing);
            AtomicReference<Runnable> flush = new AtomicReference<>();
            thread.when(() -> UserThread.runAfter(any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
                flush.set(invocation.getArgument(0));
                return mock(Timer.class);
            });
            StatisticsFixture fixture = new StatisticsFixture();
            fixture.legacy.put(new P2PDataStorage.ByteArray(conflicting.getHash()), conflicting);
            fixture.manager.onAllServicesInitialized();
            fixture.listener.get().onAdded(signed);
            flush.get().run();
            assertEquals(List.of(early), fixture.manager.getTradeStatisticsListCopy());
        }
    }

    @Test
    public void knownAttestationsAreNotRepublishedAndOnlyAuthorizedArbitratorsSign() throws CryptoException {
        try (MockedStatic<ArbitratorManager> roots = authorizeSigner(); MockedStatic<UserThread> thread = mockStatic(UserThread.class);
             MockedStatic<TradeStatistics3> statisticsFactory = mockStatic(TradeStatistics3.class, CALLS_REAL_METHODS)) {
            Trade trade = mock(Trade.class);
            when(trade.shouldPublishTradeStatistics()).thenReturn(true);
            when(trade.isArbitrator()).thenReturn(true);
            TradeStatistics3 statistics = statistics();
            statisticsFactory.when(() -> TradeStatistics3.fromV0(trade, null, true)).thenReturn(statistics);
            statisticsFactory.when(() -> TradeStatistics3.fromV1(trade, null, true)).thenReturn(statistics);
            statisticsFactory.when(() -> TradeStatistics3.fromV2(trade, null, true)).thenReturn(statistics);
            SignedTradeStatistics signed = SignedTradeStatistics.sign(statistics, arbitrator, keyRing);
            StatisticsFixture fixture = new StatisticsFixture();
            fixture.legacy.put(new P2PDataStorage.ByteArray(statistics.getHash()), statistics);
            fixture.signed.put(new P2PDataStorage.ByteArray(signed.getHash()), signed);
            List<Runnable> publications = new ArrayList<>();
            thread.when(() -> UserThread.runAfterRandomDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        publications.add(invocation.getArgument(0));
                        return mock(Timer.class);
                    });
            fixture.manager.onAllServicesInitialized();
            fixture.manager.maybePublishTradeStatistics(trade, null, true);
            assertEquals(0, publications.size());
            verify(fixture.p2p, never()).addPersistableNetworkPayload(any(), anyBoolean());
            StatisticsFixture unsignedFixture = new StatisticsFixture();
            unsignedFixture.legacy.put(new P2PDataStorage.ByteArray(statistics.getHash()), statistics);
            unsignedFixture.manager.onAllServicesInitialized();
            when(trade.isArbitrator()).thenReturn(false);
            unsignedFixture.manager.maybePublishTradeStatistics(trade, null, true);
            assertEquals(0, publications.size());
            when(trade.isArbitrator()).thenReturn(true);
            when(trade.shouldPublishTradeStatistics()).thenReturn(false);
            unsignedFixture.manager.maybePublishTradeStatistics(trade, null, true);
            assertEquals(0, publications.size());
            when(trade.shouldPublishTradeStatistics()).thenReturn(true);
            roots.when(ArbitratorManager::getRegistrationPubKeys).thenReturn(List.of());
            unsignedFixture.manager.maybePublishTradeStatistics(trade, null, true);
            assertEquals(0, publications.size());
        }
    }

    private final class StatisticsFixture {
        private final P2PService p2p = mock(P2PService.class);
        private final Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> legacy = new HashMap<>();
        private final Map<P2PDataStorage.ByteArray, PersistableNetworkPayload> signed = new HashMap<>();
        private final AtomicReference<AppendOnlyDataStoreListener> listener = new AtomicReference<>();
        private final TradeStatisticsManager manager;

        private StatisticsFixture() {
            P2PDataStorage storage = mock(P2PDataStorage.class);
            when(p2p.getP2PDataStorage()).thenReturn(storage);
            doAnswer(invocation -> {
                listener.set(invocation.getArgument(0));
                return null;
            }).when(storage).addAppendOnlyDataStoreListener(any());
            TradeStatistics3StorageService legacyStore = mock(TradeStatistics3StorageService.class);
            when(legacyStore.getMapOfAllData()).thenReturn(legacy);
            SignedTradeStatisticsStorageService signedStore = mock(SignedTradeStatisticsStorageService.class);
            when(signedStore.getMap()).thenReturn(signed);
            User user = mock(User.class);
            when(user.getRegisteredArbitrator()).thenReturn(arbitrator);
            manager = new TradeStatisticsManager(p2p, mock(PriceFeedService.class), legacyStore,
                    mock(AppendOnlyDataStoreService.class), signedStore, keyRing, user, dir, false);
        }
    }
}
