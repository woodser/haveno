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

package haveno.core.trade.statistics;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import haveno.common.app.Capabilities;
import haveno.common.app.Capability;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.crypto.CryptoException;
import haveno.common.crypto.Hash;
import haveno.common.crypto.KeyRing;
import haveno.common.crypto.Sig;
import haveno.core.support.dispute.arbitration.arbitrator.Arbitrator;
import haveno.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import haveno.network.p2p.storage.payload.CapabilityRequiringPayload;
import haveno.network.p2p.storage.payload.DateSortedTruncatablePayload;
import haveno.network.p2p.storage.payload.InvalidPersistableNetworkPayloadException;
import haveno.network.p2p.storage.payload.ProcessOncePersistableNetworkPayload;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

public final class SignedTradeStatistics implements ProcessOncePersistableNetworkPayload,
        CapabilityRequiringPayload, DateSortedTruncatablePayload {
    private static final int VERSION = 1;
    private static final byte[] DOMAIN = "Haveno signed trade statistics\0".getBytes(StandardCharsets.UTF_8);
    private static final Cache<ByteString, PublicKey> CERTIFICATES = CacheBuilder.newBuilder().maximumSize(256).build();

    private final protobuf.SignedTradeStatistics proto;
    private final byte[] hash;

    private SignedTradeStatistics(protobuf.SignedTradeStatistics proto) {
        this.proto = proto;
        this.hash = Hash.getSha256Ripemd160hash(signaturePayload(proto));
    }

    public static SignedTradeStatistics sign(TradeStatistics3 statistics, Arbitrator arbitrator, KeyRing keyRing) throws CryptoException {
        if (!Arrays.equals(arbitrator.getPubKeyRing().getSignaturePubKeyBytes(), keyRing.getPubKeyRing().getSignaturePubKeyBytes()))
            throw new IllegalArgumentException("Arbitrator signing key does not match registration");
        protobuf.SignedTradeStatistics unsigned = protobuf.SignedTradeStatistics.newBuilder()
                .setTradeStatistics(statistics.toProtoTradeStatistics3())
                .setArbitratorPubKey(ByteString.copyFrom(keyRing.getPubKeyRing().getSignaturePubKeyBytes()))
                .setRegistrationPubKey(ByteString.copyFrom(arbitrator.getRegistrationPubKey()))
                .setRegistrationSignature(arbitrator.getRegistrationSignature())
                .setNetwork(network())
                .setVersion(VERSION)
                .build();
        return fromProto(unsigned.toBuilder()
                .setSignature(ByteString.copyFrom(Sig.sign(keyRing.getSignatureKeyPair().getPrivate(), signaturePayload(unsigned))))
                .build());
    }

    public static SignedTradeStatistics fromProto(protobuf.SignedTradeStatistics proto) {
        if (!proto.hasTradeStatistics() || proto.getSerializedSize() > 16384 ||
                proto.getArbitratorPubKey().isEmpty() || proto.getArbitratorPubKey().size() > 1024 ||
                proto.getRegistrationPubKey().size() != 33 || proto.getRegistrationSignature().length() > 128 ||
                proto.getSignature().isEmpty() || proto.getSignature().size() > 128 ||
                proto.getVersion() != VERSION || !proto.getNetwork().equals(network()))
            throw new InvalidPersistableNetworkPayloadException("Invalid signed trade statistics format");

        // Check authorization even on cache hits, and never trust a key supplied only by the record itself.
        if (!ArbitratorManager.getRegistrationPubKeys().contains(Utils.HEX.encode(proto.getRegistrationPubKey().toByteArray())))
            throw new InvalidPersistableNetworkPayloadException("Unauthorized trade statistics signer");
        TradeStatistics3 statistics = TradeStatistics3.fromProto(proto.getTradeStatistics());
        protobuf.SignedTradeStatistics canonical = proto.toBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.getDefaultInstance())
                .setTradeStatistics(statistics.toProtoTradeStatistics3())
                .build();
        try {
            protobuf.SignedTradeStatistics certificate = canonical.toBuilder().clearTradeStatistics().clearSignature().build();
            ByteString certificateHash = ByteString.copyFrom(Hash.getSha256Hash(deterministicBytes(certificate)));
            PublicKey publicKey = CERTIFICATES.getIfPresent(certificateHash);
            if (publicKey == null) {
                ECKey.fromPublicOnly(canonical.getRegistrationPubKey().toByteArray()).verifyMessage(
                        Utils.HEX.encode(canonical.getArbitratorPubKey().toByteArray()), canonical.getRegistrationSignature());
                publicKey = Sig.getPublicKeyFromBytes(canonical.getArbitratorPubKey().toByteArray());
                CERTIFICATES.put(certificateHash, publicKey);
            }
            if (!Sig.verify(publicKey, signaturePayload(canonical), canonical.getSignature().toByteArray()))
                throw new InvalidPersistableNetworkPayloadException("Invalid trade statistics signature");
            return new SignedTradeStatistics(canonical);
        } catch (InvalidPersistableNetworkPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidPersistableNetworkPayloadException("Invalid trade statistics attestation");
        }
    }

    private static String network() {
        return Config.baseCurrencyNetwork().name() + ":" + Version.P2P_NETWORK_VERSION;
    }

    private static byte[] signaturePayload(protobuf.SignedTradeStatistics proto) {
        byte[] data = deterministicBytes(proto.toBuilder().clearSignature().clearRegistrationSignature().build());
        byte[] result = Arrays.copyOf(DOMAIN, DOMAIN.length + data.length);
        System.arraycopy(data, 0, result, DOMAIN.length, data.length);
        return result;
    }

    private static byte[] deterministicBytes(protobuf.SignedTradeStatistics proto) {
        byte[] bytes = new byte[proto.getSerializedSize()];
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.useDeterministicSerialization();
        try {
            proto.writeTo(output);
            output.checkNoSpaceLeft();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public TradeStatistics3 getTradeStatistics() {
        // TradeStatistics3 exposes mutable metadata; copy the verified fields without rehashing them.
        protobuf.TradeStatistics3 statistics = proto.getTradeStatistics();
        return new TradeStatistics3(statistics.getCurrency(), statistics.getPrice(), statistics.getAmount(),
                statistics.getPaymentMethod(), statistics.getDate(), statistics.getArbitrator().isEmpty() ? null : statistics.getArbitrator(),
                statistics.getExtraDataCount() == 0 ? null : new HashMap<>(statistics.getExtraDataMap()), statistics.getHash().toByteArray());
    }

    public protobuf.SignedTradeStatistics toProtoSignedTradeStatistics() {
        return proto;
    }

    @Override
    public protobuf.PersistableNetworkPayload toProtoMessage() {
        return protobuf.PersistableNetworkPayload.newBuilder().setSignedTradeStatistics(proto).build();
    }

    @Override
    public byte[] getHash() {
        return hash.clone();
    }

    @Override
    public boolean verifyHashSize() {
        return hash.length == 20;
    }

    @Override
    public Capabilities getRequiredCapabilities() {
        return new Capabilities(Capability.SIGNED_TRADE_STATISTICS);
    }

    @Override
    public Date getDate() {
        return new Date(proto.getTradeStatistics().getDate());
    }

    @Override
    public int maxItems() {
        return 3000;
    }
}
