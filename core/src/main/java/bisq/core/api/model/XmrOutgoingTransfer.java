package bisq.core.api.model;

import bisq.core.btc.model.RawTransactionInput;

import bisq.common.Payload;
import bisq.common.proto.ProtoUtil;

import bisq.proto.grpc.XmrOutgoingTransferOrBuilder;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;


import javax.annotation.Nullable;

import static bisq.core.api.model.XmrDestination.toXmrDestination;



import monero.wallet.model.MoneroIncomingTransfer;
import monero.wallet.model.MoneroOutgoingTransfer;

@Getter
public class XmrOutgoingTransfer implements Payload {

    private final List<Integer> subaddressIndices;
    @Nullable
    @Setter
    private final List<String> addresses;
    @Nullable
    @Setter
    private final List<XmrDestination> destinations;

    public XmrOutgoingTransfer(XmrOutgoingTransferBuilder builder) {
        this.subaddressIndices = builder.subaddressIndices;
        this.addresses = builder.addresses;
        this.destinations = builder.destinations;
    }

    public static XmrOutgoingTransfer toXmrOutgoingTransfer(MoneroOutgoingTransfer tx) {
        List<String> addresses = tx.getAddresses() == null ? new ArrayList<>() : tx.getAddresses();
        List<XmrDestination> destinations = tx.getDestinations() == null ? new ArrayList<XmrDestination>() :
                tx.getDestinations().stream()
                .map(s -> toXmrDestination(s))
                .collect(Collectors.toList());
        XmrOutgoingTransferBuilder builder = new XmrOutgoingTransferBuilder()
                .withSubaddressIndices(tx.getSubaddressIndices());
                Optional.ofNullable(addresses).ifPresent(e ->builder.withAddresses(addresses));
                Optional.ofNullable(destinations).ifPresent(e ->builder.withDestinations(destinations));
            return builder.build();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public bisq.proto.grpc.XmrOutgoingTransfer toProtoMessage() {
        var builder = bisq.proto.grpc.XmrOutgoingTransfer.newBuilder();
                    Optional.ofNullable(subaddressIndices).ifPresent(e -> builder.addAllSubaddressIndices(subaddressIndices));
                    Optional.ofNullable(addresses).ifPresent(e -> builder.addAllAddresses(addresses));
                    Optional.ofNullable(destinations).ifPresent(e -> builder.addAllDestinations(ProtoUtil.collectionToProto(destinations, bisq.proto.grpc.XmrDestination.class)));
                return builder.build();
    }

    public static XmrOutgoingTransfer fromProto(bisq.proto.grpc.XmrOutgoingTransfer proto) {
        List<XmrDestination> destinations = proto.getDestinationsList().isEmpty() ?
                null : proto.getDestinationsList().stream()
                .map(XmrDestination::fromProto).collect(Collectors.toList());
        return new XmrOutgoingTransferBuilder()
                .withSubaddressIndices(proto.getSubaddressIndicesList())
                .withAddresses(proto.getAddressesList())
                .withDestinations(destinations)
                .build();
    }

    public static class XmrOutgoingTransferBuilder {
        private List<Integer> subaddressIndices;
        private List<String> addresses;
        private List<XmrDestination> destinations;

        public XmrOutgoingTransferBuilder withSubaddressIndices(List<Integer> subaddressIndices) {
            this.subaddressIndices = subaddressIndices;
            return this;
        }

        public XmrOutgoingTransferBuilder withAddresses(List<String> addresses) {
            this.addresses = addresses;
            return this;
        }

        public XmrOutgoingTransferBuilder withDestinations(List<XmrDestination> destinations) {
            this.destinations = destinations;
            return this;
        }

        public XmrOutgoingTransfer build() {
            return new XmrOutgoingTransfer(this);
        }

    }

    @Override
    public String toString() {
        return "XmrOutgoingTransfer{" +
                "subaddressIndices=" + subaddressIndices +
                ", addresses=" + addresses +
                ", destinations=" + destinations +
                '}';
    }
}
