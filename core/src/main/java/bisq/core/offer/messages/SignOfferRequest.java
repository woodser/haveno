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

package bisq.core.offer.messages;

import bisq.common.crypto.PubKeyRing;
import bisq.core.offer.OfferPayload;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.proto.CoreProtoResolver;
import bisq.network.p2p.DirectMessage;
import bisq.network.p2p.NodeAddress;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
public final class SignOfferRequest extends OfferMessage implements DirectMessage {
    private final NodeAddress senderNodeAddress;
    private final PubKeyRing senderPubKeyRing;
    private final String senderAccountId;
    private final OfferPayload offerPayload;
    private final long currentDate;
    private final String reserveTxHex;

    public SignOfferRequest(String offerId,
                                     NodeAddress senderNodeAddress,
                                     PubKeyRing senderPubKeyRing,
                                     String senderAccountId,
                                     OfferPayload offerPayload,
                                     String uid,
                                     int messageVersion,
                                     long currentDate,
                                     String reserveTxHex) {
        super(messageVersion, offerId, uid);
        this.senderNodeAddress = senderNodeAddress;
        this.senderPubKeyRing = senderPubKeyRing;
        this.senderAccountId = senderAccountId;
        this.offerPayload = offerPayload;
        this.currentDate = currentDate;
        this.reserveTxHex = reserveTxHex;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public protobuf.NetworkEnvelope toProtoNetworkEnvelope() {
        protobuf.SignOfferRequest.Builder builder = protobuf.SignOfferRequest.newBuilder()
                .setOfferId(offerId)
                .setSenderNodeAddress(senderNodeAddress.toProtoMessage())
                .setSenderPubKeyRing(senderPubKeyRing.toProtoMessage())
                .setSenderAccountId(senderAccountId)
                .setOfferPayload(offerPayload.toProtoMessage().getOfferPayload())
                .setUid(uid)
                .setCurrentDate(currentDate)
                .setReserveTxHex(reserveTxHex);

        return getNetworkEnvelopeBuilder().setSignOfferRequest(builder).build();
    }

    public static SignOfferRequest fromProto(protobuf.SignOfferRequest proto,
                                                      int messageVersion) {
        return new SignOfferRequest(proto.getOfferId(),
                NodeAddress.fromProto(proto.getSenderNodeAddress()),
                PubKeyRing.fromProto(proto.getSenderPubKeyRing()),
                proto.getSenderAccountId(),
                OfferPayload.fromProto(proto.getOfferPayload()),
                proto.getUid(),
                messageVersion,
                proto.getCurrentDate(),
                proto.getReserveTxHex());
    }

    @Override
    public String toString() {
        return "SignOfferRequest {" +
                "\n     senderNodeAddress=" + senderNodeAddress +
                ",\n     pubKeyRing=" + senderPubKeyRing +
                ",\n     currentDate=" + currentDate +
                ",\n     reserveTxHex='" + reserveTxHex +
                "\n} " + super.toString();
    }
}
