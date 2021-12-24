package bisq.core.api.model;

import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;

import com.google.common.annotations.VisibleForTesting;
import bisq.core.api.model.XmrIncomingTransfer;
import bisq.core.api.model.XmrIncomingTransfer;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import static bisq.core.api.model.XmrIncomingTransfer.toXmrIncomingTransfer;
import static bisq.core.api.model.XmrOutgoingTransfer.toXmrOutgoingTransfer;



import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroTxWallet;

@Getter
public class XmrTx implements Payload {

    private final String hash;
    @Nullable
    @Setter
    private final long timestamp;
    @Nullable
    private final List<XmrIncomingTransfer> incomingTransfers;
    @Nullable
    private final XmrOutgoingTransfer outgoingTransfer;
    private final String metadata;

    public XmrTx(XmrTxBuilder builder) {
        this.hash = builder.hash;
        this.timestamp = builder.timestamp;
        this.incomingTransfers = builder.incomingTransfers;
        this.outgoingTransfer = builder.outgoingTransfer;
        this.metadata = builder.metadata;
    }

    public static XmrTx toXmrTx(MoneroTxWallet tx){
        long timestamp = tx.getBlock() == null ? 0 : tx.getBlock().getTimestamp();
        List<XmrIncomingTransfer> incomingTransfers = tx.getIncomingTransfers() == null ? new ArrayList<>() :
                tx.getIncomingTransfers().stream()
                .map(s -> toXmrIncomingTransfer(s))
                .collect(Collectors.toList());
        XmrOutgoingTransfer.XmrOutgoingTransferBuilder b = new XmrOutgoingTransfer.XmrOutgoingTransferBuilder();
        b.withSubaddressIndices(new ArrayList<>());
        b.withDestinations(new ArrayList<>());
        b.withAddresses(new ArrayList<>());
        XmrOutgoingTransfer outgoingTransfer = tx.getOutgoingTransfer() == null ?
                new XmrOutgoingTransfer(b) : toXmrOutgoingTransfer(tx.getOutgoingTransfer());
        XmrTxBuilder builder = new XmrTxBuilder()
                .withHash(tx.getHash())
                .withMetadata(tx.getMetadata());
                Optional.ofNullable(timestamp).ifPresent(e ->builder.withTimestamp(timestamp));
                Optional.ofNullable(outgoingTransfer).ifPresent(e ->builder.withOutgoingTransfer(outgoingTransfer));
                Optional.ofNullable(incomingTransfers).ifPresent(e ->builder.withIncomingTransfers(incomingTransfers));
        return builder.build();
    }



    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrTx toProtoMessage() {
        bisq.proto.grpc.XmrTx.Builder builder = bisq.proto.grpc.XmrTx.newBuilder()
                .setHash(hash);
                Optional.ofNullable(metadata).ifPresent(e -> builder.setMetadata(metadata));
                Optional.ofNullable(timestamp).ifPresent(e -> builder.setTimestamp(timestamp));
                Optional.ofNullable(outgoingTransfer).ifPresent(e -> builder.setOutgoingTransfer(outgoingTransfer.toProtoMessage()));
                Optional.ofNullable(incomingTransfers).ifPresent(e -> builder.addAllIncomingTransfers(ProtoUtil.collectionToProto(incomingTransfers, bisq.proto.grpc.XmrIncomingTransfer.class)));
        return builder.build();
    }

    public static XmrTx fromProto(bisq.proto.grpc.XmrTx proto) {
        return new XmrTxBuilder()
                .withHash(proto.getHash())
                .withTimestamp(proto.getTimestamp())
                .withIncomingTransfers(
                    proto.getIncomingTransfersList().stream()
                        .map(XmrIncomingTransfer::fromProto)
                        .collect(Collectors.toList()))
                .withOutgoingTransfer(XmrOutgoingTransfer.fromProto(proto.getOutgoingTransfer()))
                .withMetadata(proto.getMetadata())
                .build();
    }

    public static class XmrTxBuilder {
        private String hash;
        private long timestamp;
        private List<XmrIncomingTransfer> incomingTransfers;
        private XmrOutgoingTransfer outgoingTransfer;
        private String metadata;

        public XmrTxBuilder withHash(String hash) {
            this.hash = hash;
            return this;
        }

        public XmrTxBuilder withTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public XmrTxBuilder withIncomingTransfers(List<XmrIncomingTransfer> incomingTransfers) {
            this.incomingTransfers = incomingTransfers;
            return this;
        }

        public XmrTxBuilder withOutgoingTransfer(XmrOutgoingTransfer outgoingTransfer) {
            this.outgoingTransfer = outgoingTransfer;
            return this;
        }

        public XmrTxBuilder withMetadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public XmrTx build() { return new XmrTx(this); }
    }
/*
    @Override
    public String toString() {
        return "BtcBalanceInfo{" +
                "unlockedBalance=" + hash +
                ", lockedBalance=" + timestamp +
                ", reservedOfferBalance=" + transfers +
                ", reservedTradeBalance=" + metadata +
                '}';
    }*/
}
