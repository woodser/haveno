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

import bisq.common.UserThread;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;

@Slf4j
public class ProcessPaymentAccountPayloadRequest extends TradeTask {
    
    private Subscription tradeStateSubscription;
    
    @SuppressWarnings({"unused"})
    public ProcessPaymentAccountPayloadRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          if (trade.getTradingPeer().getPaymentAccountPayload() != null) throw new RuntimeException("Peer's payment account payload has already been set");
          
          // get peer's payment account payload
          PaymentAccountPayloadRequest request = (PaymentAccountPayloadRequest) processModel.getTradeMessage(); // TODO (woodser): verify request
          PaymentAccountPayload paymentAccountPayload = request.getPaymentAccountPayload();
          
          // verify hash of payment account payload
          byte[] peerPaymentAccountPayloadHash = trade instanceof MakerTrade ? trade.getContract().getTakerPaymentAccountPayloadHash() : trade.getContract().getMakerPaymentAccountPayloadHash();
          if (!Arrays.equals(paymentAccountPayload.getHash(), peerPaymentAccountPayloadHash)) throw new RuntimeException("Hash of peer's payment account payload does not match contract");
          
          // set payment account payload
          trade.getTradingPeer().setPaymentAccountPayload(paymentAccountPayload);
          
          // apply published transaction which notifies ui
          MoneroWallet multisigWallet = processModel.getXmrWalletService().getMultisigWallet(trade.getId());
          MoneroTxWallet makerDepositTx = checkNotNull(multisigWallet.getTx(processModel.getMaker().getDepositTxHash())); // TODO (woodser): this will fail if seeing broadcast txs is delayed
          MoneroTxWallet takerDepositTx = checkNotNull(multisigWallet.getTx(processModel.getTaker().getDepositTxHash()));
          applyPublishedDepositTxs(makerDepositTx, takerDepositTx);

          // notify trade state subscription when deposit published
          tradeStateSubscription = EasyBind.subscribe(trade.stateProperty(), newValue -> {
            if (trade.isDepositPublished()) {
              swapReservedForTradeEntry();
              UserThread.execute(this::unSubscribe);  // hack to remove tradeStateSubscription at callback
            }
          });
          
          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
    
    private void applyPublishedDepositTxs(MoneroTxWallet makerDepositTx, MoneroTxWallet takerDepositTx) {
        trade.applyDepositTxs(makerDepositTx, takerDepositTx);
        trade.setState(Trade.State.MAKER_RECEIVED_DEPOSIT_TX_PUBLISHED_MSG); // TODO (woodser): maker and taker?
        swapReservedForTradeEntry();
        UserThread.execute(this::unSubscribe); // need delay as it can be called inside the listener handler before listener and tradeStateSubscription are actually set.
    }

    private void swapReservedForTradeEntry() {
        log.info("swapReservedForTradeEntry");
        processModel.getProvider().getXmrWalletService().swapTradeEntryToAvailableEntry(trade.getId(), XmrAddressEntry.Context.RESERVED_FOR_TRADE);
    }

    private void unSubscribe() {
        if (tradeStateSubscription != null) tradeStateSubscription.unsubscribe();
    }
}
