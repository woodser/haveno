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

package bisq.core.trade.messages;

import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;

import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;

import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.ProtoUtil;
import bisq.common.util.Utilities;

import com.google.protobuf.ByteString;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Value;

import javax.annotation.Nullable;

@EqualsAndHashCode(callSuper = true)
@Value
public final class InitTradeRequest extends TradeMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final long tradeAmount;
    private final long tradePrice;
    private final long tradeFee;
    private final PaymentAccountPayload takerPaymentAccountPayload;
    private final PaymentAccountPayload makerPaymentAccountPayload;
    private final PubKeyRing pubKeyRing;
    private final String accountId;

    // added in v 0.6. can be null if we trade with an older peer
    @Nullable
    private final byte[] accountAgeWitnessSignatureOfOfferId;
    private final long currentDate;

    // XMR integration
    private final NodeAddress arbitratorNodeAddress;
    private final NodeAddress takerNodeAddress;
    private final NodeAddress makerNodeAddress;
    @Nullable
    private final String reserveTxHash;
    @Nullable
    private final String reserveTxHex;
    @Nullable
    private final String reserveTxKey;
    @Nullable
    private final String payoutAddress;

    public InitTradeRequest(String tradeId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing pubKeyRing,
                                     long tradeAmount,
                                     long tradePrice,
                                     long tradeFee,
                                     String accountId,
                                     String uid,
                                     int messageVersion,
                                     @Nullable byte[] accountAgeWitnessSignatureOfOfferId,
                                     long currentDate,
                                     NodeAddress takerNodeAddress,
                                     NodeAddress makerNodeAddress,
                                     NodeAddress arbitratorNodeAddress,
                                     PaymentAccountPayload takerPaymentAccountPayload,
                                     PaymentAccountPayload makerPaymentAccountPayload,
                                     @Nullable String reserveTxHash,
                                     @Nullable String reserveTxHex,
                                     @Nullable String reserveTxKey,
                                     @Nullable String payoutAddress) {
        super(messageVersion, tradeId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.pubKeyRing = pubKeyRing;
        this.tradeAmount = tradeAmount;
        this.tradePrice = tradePrice;
        this.tradeFee = tradeFee;
        this.accountId = accountId;
        this.accountAgeWitnessSignatureOfOfferId = accountAgeWitnessSignatureOfOfferId;
        this.currentDate = currentDate;
        this.takerNodeAddress = takerNodeAddress;
        this.makerNodeAddress = makerNodeAddress;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.takerPaymentAccountPayload = takerPaymentAccountPayload;
        this.makerPaymentAccountPayload = makerPaymentAccountPayload;
        this.reserveTxHash = reserveTxHash;
        this.reserveTxHex = reserveTxHex;
        this.reserveTxKey = reserveTxKey;
        this.payoutAddress = payoutAddress;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.InitTradeRequest.Builder builder = protobuf.InitTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setTakerNodeAddress(takerNodeAddress.toProtoMessage())
                .setMakerNodeAddress(makerNodeAddress.toProtoMessage())
                .setArbitratorNodeAddress(arbitratorNodeAddress.toProtoMessage())
                .setTradeAmount(tradeAmount)
                .setTradePrice(tradePrice)
                .setTradeFee(tradeFee)
                .setPubKeyRing(pubKeyRing.toProtoMessage())
                .setTakerPaymentAccountPayload((protobuf.PaymentAccountPayload) takerPaymentAccountPayload.toProtoMessage())
                .setMakerPaymentAccountPayload((protobuf.PaymentAccountPayload) makerPaymentAccountPayload.toProtoMessage())
                .setAccountId(accountId)
                .setUid(uid);

        Optional.ofNullable(reserveTxHash).ifPresent(e -> builder.setReserveTxHash(reserveTxHash));
        Optional.ofNullable(reserveTxHex).ifPresent(e -> builder.setReserveTxHex(reserveTxHex));
        Optional.ofNullable(reserveTxKey).ifPresent(e -> builder.setReserveTxKey(reserveTxKey));
        Optional.ofNullable(payoutAddress).ifPresent(e -> builder.setPayoutAddress(payoutAddress));
        Optional.ofNullable(accountAgeWitnessSignatureOfOfferId).ifPresent(e -> builder.setAccountAgeWitnessSignatureOfOfferId(ByteString.copyFrom(e)));
        builder.setCurrentDate(currentDate);

        return getNetworkEnvelopeBuilder().setInitTradeRequest(builder).build();
    }

    public static InitTradeRequest fromProto(protobuf.InitTradeRequest proto,
                                                      CoreProtoResolver coreProtoResolver,
                                                      int messageVersion) {
        return new InitTradeRequest(proto.getTradeId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getPubKeyRing()),
                proto.getTradeAmount(),
                proto.getTradePrice(),
                proto.getTradeFee(),
                proto.getAccountId(),
                proto.getUid(),
                messageVersion,
                ProtoUtil.byteArrayOrNullFromProto(proto.getAccountAgeWitnessSignatureOfOfferId()),
                proto.getCurrentDate(),
                NodeAddress.fromProto(proto.getTakerNodeAddress()),
                NodeAddress.fromProto(proto.getMakerNodeAddress()),
                NodeAddress.fromProto(proto.getArbitratorNodeAddress()),
                coreProtoResolver.fromProto(proto.getTakerPaymentAccountPayload()),
                coreProtoResolver.fromProto(proto.getMakerPaymentAccountPayload()),
                proto.getReserveTxHash(),
                proto.getReserveTxHex(),
                proto.getReserveTxKey(),
                proto.getPayoutAddress());
    }

    @Override
    public String toString() {
        return "InitTradeRequest{" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     tradeAmount=" + tradeAmount +
                ",\n     tradePrice=" + tradePrice +
                ",\n     tradeFee=" + tradeFee +
                ",\n     pubKeyRing=" + pubKeyRing +
                ",\n     paymentAccountPayload=" + takerPaymentAccountPayload +
                ",\n     paymentAccountPayload='" + accountId + '\'' +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     accountAgeWitnessSignatureOfOfferId=" + Utilities.bytesAsHexString(accountAgeWitnessSignatureOfOfferId) +
                ",\n     currentDate=" + currentDate +
                ",\n     reserveTxHash=" + reserveTxHash +
                ",\n     reserveTxHex=" + reserveTxHex +
                ",\n     reserveTxKey=" + reserveTxKey +
                ",\n     payoutAddress=" + payoutAddress +
                "\n} " + super.toString();
    }
}
