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

import com.google.protobuf.Message;
import haveno.network.p2p.storage.payload.InvalidPersistableNetworkPayloadException;
import haveno.network.p2p.storage.persistence.PersistableNetworkPayloadStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class SignedTradeStatisticsStore extends PersistableNetworkPayloadStore<SignedTradeStatistics> {
    public SignedTradeStatisticsStore() {
    }

    private SignedTradeStatisticsStore(List<SignedTradeStatistics> statistics) {
        super(statistics);
    }

    @Override
    public Message toProtoMessage() {
        protobuf.SignedTradeStatisticsStore.Builder builder = protobuf.SignedTradeStatisticsStore.newBuilder();
        map.values().forEach(value -> builder.addItems(((SignedTradeStatistics) value).toProtoSignedTradeStatistics()));
        return protobuf.PersistableEnvelope.newBuilder().setSignedTradeStatisticsStore(builder).build();
    }

    public static SignedTradeStatisticsStore fromProto(protobuf.SignedTradeStatisticsStore proto) {
        List<SignedTradeStatistics> statistics = new ArrayList<>();
        for (protobuf.SignedTradeStatistics value : proto.getItemsList()) {
            try {
                statistics.add(SignedTradeStatistics.fromProto(value));
            } catch (InvalidPersistableNetworkPayloadException e) {
                log.warn("Ignoring invalid signed trade statistics from store: {}", e.getMessage());
            }
        }
        return new SignedTradeStatisticsStore(statistics);
    }
}
