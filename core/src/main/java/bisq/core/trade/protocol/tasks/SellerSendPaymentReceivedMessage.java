/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade.protocol.tasks;

import bisq.core.account.sign.SignedWitness;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.trade.messages.TradeMailboxMessage;
import bisq.network.p2p.NodeAddress;
import bisq.common.crypto.PubKeyRing;
import bisq.common.taskrunner.TaskRunner;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@EqualsAndHashCode(callSuper = true)
@Slf4j
public abstract class SellerSendPaymentReceivedMessage extends SendMailboxMessageTask {
    SignedWitness signedWitness = null;

    public SellerSendPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    protected abstract NodeAddress getReceiverNodeAddress();

    protected abstract PubKeyRing getReceiverPubKeyRing();

    @Override
    protected void run() {
        try {
            runInterceptHook();

            if (trade.getPayoutTxHex() == null) {
                log.error("Payout tx is null");
                failed("Payout tx is null");
                return;
            }

            super.run();
        } catch (Throwable t) {
            failed(t);
        }
    }

    @Override
    protected TradeMailboxMessage getTradeMailboxMessage(String id) {
        checkNotNull(trade.getPayoutTxHex(), "Payout tx must not be null");

        // TODO: sign witness
        // AccountAgeWitnessService accountAgeWitnessService = processModel.getAccountAgeWitnessService();
        // if (accountAgeWitnessService.isSignWitnessTrade(trade)) {
        //     // Broadcast is done in accountAgeWitness domain.
        //     accountAgeWitnessService.traderSignAndPublishPeersAccountAgeWitness(trade).ifPresent(witness -> signedWitness = witness);
        // }

        return new PaymentReceivedMessage(
                id,
                processModel.getMyNodeAddress(),
                signedWitness,
                trade.getPayoutTxHex(),
                trade.getSelf().getUpdatedMultisigHex()
        );
    }

    @Override
    protected void setStateSent() {
        trade.setState(Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG); // TODO: setStateIfProgress()
        log.info("{} sent: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateArrived() {
        trade.setState(Trade.State.SELLER_SAW_ARRIVED_PAYMENT_RECEIVED_MSG);
        log.info("{} arrived: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateStoredInMailbox() {
        trade.setState(Trade.State.SELLER_STORED_IN_MAILBOX_PAYMENT_RECEIVED_MSG);
        log.info("{} stored in mailbox: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }

    @Override
    protected void setStateFault() {
        trade.setState(Trade.State.SELLER_SEND_FAILED_PAYMENT_RECEIVED_MSG);
        log.error("{} failed: tradeId={} at peer {} SignedWitness {}", getClass().getSimpleName(), trade.getId(), getReceiverNodeAddress(), signedWitness);
        processModel.getTradeManager().requestPersistence();
    }
}
