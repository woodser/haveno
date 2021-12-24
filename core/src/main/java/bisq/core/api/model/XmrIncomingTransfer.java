package bisq.core.api.model;

import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;

import bisq.proto.grpc.XmrIncomingTransferOrBuilder;

import com.google.common.annotations.VisibleForTesting;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Getter;



import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroOutgoingTransfer;

@Getter
public class XmrIncomingTransfer implements Payload {
    private final Integer subaddressIndex;
    private final String address;
    private final Long numSuggestedConfirmations;


    public XmrIncomingTransfer(XmrIncomingTransferBuilder builder) {
        this.subaddressIndex = builder.subaddressIndex;
        this.address = builder.address;
        this.numSuggestedConfirmations = builder.numSuggestedConfirmations;
    }

    public static XmrIncomingTransfer toXmrIncomingTransfer(MoneroIncomingTransfer tx) {
        return new XmrIncomingTransferBuilder()
                .withSubaddressIndex(tx.getSubaddressIndex())
                .withAddress(tx.getAddress())
                .withNumSuggestedConfirmations(tx.getNumSuggestedConfirmations())
                .build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrIncomingTransfer toProtoMessage() {
        return bisq.proto.grpc.XmrIncomingTransfer.newBuilder()
                .setSubaddressIndex(subaddressIndex)
                .setAddress(address)
                .setNumSuggestedConfirmations(numSuggestedConfirmations)
                .build();
    }

    public static XmrIncomingTransfer fromProto(bisq.proto.grpc.XmrIncomingTransfer proto) {
        return new XmrIncomingTransferBuilder()
                .withSubaddressIndex(proto.getSubaddressIndex())
                .withAddress(proto.getAddress())
                .withNumSuggestedConfirmations(proto.getNumSuggestedConfirmations())
                .build();
    }

    public static class XmrIncomingTransferBuilder {
        private Integer subaddressIndex;
        private String address;
        private Long numSuggestedConfirmations;

        public XmrIncomingTransferBuilder withSubaddressIndex(Integer subaddressIndex) {
            this.subaddressIndex = subaddressIndex;
            return this;
        }

        public XmrIncomingTransferBuilder withAddress(String address) {
            this.address = address;
            return this;
        }

        public XmrIncomingTransferBuilder withNumSuggestedConfirmations(Long numSuggestedConfirmations) {
            this.numSuggestedConfirmations = numSuggestedConfirmations;
            return this;
        }

        public XmrIncomingTransfer build() {
            return new XmrIncomingTransfer(this);
        }

    }

    @Override
    public String toString() {
        return "XmrIncomingTransfer{" +
                "subaddressIndex=" + subaddressIndex +
                ", address=" + address +
                ", numSuggestedConfirmations=" + numSuggestedConfirmations +
                '}';
    }
}
