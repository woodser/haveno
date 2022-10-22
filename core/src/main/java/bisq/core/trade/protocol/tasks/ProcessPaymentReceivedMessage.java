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
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.BuyerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentReceivedMessage;
import bisq.core.util.Validator;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class ProcessPaymentReceivedMessage extends TradeTask {
    public ProcessPaymentReceivedMessage(TaskRunner<Trade> taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("current trade state " + trade.getState());
            PaymentReceivedMessage message = (PaymentReceivedMessage) processModel.getTradeMessage();
            Validator.checkTradeId(processModel.getOfferId(), message);
            checkNotNull(message);
            checkArgument(message.getPayoutTxHex() != null);

            // update to the latest peer address of our peer if the message is correct
            trade.getSeller().setNodeAddress(processModel.getTempTradingPeerNodeAddress());

            // get multisig wallet
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            MoneroWallet multisigWallet = walletService.getMultisigWallet(trade.getId());

            // import multisig hex
            if (message.getUpdatedMultisigHex() != null) multisigWallet.importMultisigHex(message.getUpdatedMultisigHex());

            // handle if payout tx not published
            if (trade.getPayoutState().ordinal() < Trade.PayoutState.PUBLISHED.ordinal()) {
                if (trade instanceof BuyerTrade) {

                    // verify, sign (if unsigned), and publish payout tx
                    boolean previouslySigned = trade.getPayoutTxHex() != null;
                    if (previouslySigned) {
                        log.info("{} publishing signed payout tx from seller", trade.getClass().getSimpleName());
                    } else {
                        log.info("{} verifying, signing, and publishing seller's payout tx", trade.getClass().getSimpleName());
                    }
                    trade.verifyPayoutTx(message.getPayoutTxHex(), !previouslySigned, true);
                    // TODO (woodser): send PayoutTxPublishedMessage to seller

                    // mark address entries as available
                    processModel.getXmrWalletService().resetAddressEntriesForPendingTrade(trade.getId());
                } else if (trade instanceof ArbitratorTrade) { // TODO: handle arbitrator
                    log.warn("Need to implement arbitrator ProcessPaymentReceivedMessage");
                    trade.listenForPayoutTx();
                    //throw new RuntimeException("Not implemented");
                }
            } else {
                log.info("We got the payout tx already set from the payout listener and do nothing here. trade ID={}", trade.getId());
            }

            SignedWitness signedWitness = message.getSignedWitness();
            if (signedWitness != null) {
                // We received the signedWitness from the seller and publish the data to the network.
                // The signer has published it as well but we prefer to re-do it on our side as well to achieve higher
                // resilience.
                processModel.getAccountAgeWitnessService().publishOwnSignedWitness(signedWitness);
            }

            // TODO: if arbitrator trade, complete trade
            trade.setStateIfValidTransitionTo(trade instanceof ArbitratorTrade ? Trade.State.TRADE_COMPLETED : Trade.State.SELLER_SENT_PAYMENT_RECEIVED_MSG);
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
