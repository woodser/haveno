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

package bisq.core.trade.protocol.tasks;

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.network.p2p.SendDirectMessageListener;
import com.google.common.base.Charsets;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Arbitrator sends InitMultisigMessage to maker and taker if both reserve txs received.
 */
@Slf4j
public class ArbitratorSendsInitMultisigMessagesIfFundsReserved extends TradeTask {
    @SuppressWarnings({"unused"})
    public ArbitratorSendsInitMultisigMessagesIfFundsReserved(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // skip if arbitrator does not have maker reserve tx
            if (processModel.getMaker().getReserveTxHash() == null) { // TODO (woodser): need to read this from signed offer store
                log.info("Arbitrator does not have maker reserve tx for offerId {}, waiting to receive before initializing multisig wallet", processModel.getOffer().getId());
                complete();
                return;
            }
            
            // prepare multisig hex
            
            // start creating multisig with maker and taker by sending InitMultisigMessages
            if (true) throw new RuntimeException("Not yet implemented!");
            
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
