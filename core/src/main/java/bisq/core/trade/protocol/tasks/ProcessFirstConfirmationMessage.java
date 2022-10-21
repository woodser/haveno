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


import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.FirstConfirmationMessage;
import bisq.core.trade.protocol.TradingPeer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessFirstConfirmationMessage extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessFirstConfirmationMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // update peer node address if not from arbitrator
          // TODO: update based on pub key ring?
        //   if (!trade.getArbitrator().getNodeAddress().equals(processModel.getTempTradingPeerNodeAddress())) {
        //     System.out.println("ProcessFirstConfirmation message 2");
        //       trade.getTradingPeer().setNodeAddress(processModel.getTempTradingPeerNodeAddress());
        //       System.out.println("ProcessFirstConfirmation message 3");
        //   }

          // decrypt seller payment account payload if key given
          FirstConfirmationMessage request = (FirstConfirmationMessage) processModel.getTradeMessage();
          if (request.getSellerPaymentAccountKey() != null && trade.getTradingPeer().getPaymentAccountPayload() == null) {
              log.info(trade.getClass().getSimpleName() + " decryping using seller payment account key: " + request.getSellerPaymentAccountKey());
              trade.decryptPeersPaymentAccountPayload(request.getSellerPaymentAccountKey());
          }

          // store updated multisig hex for processing on payment sent
          TradingPeer sender = trade.getTradingPeer(processModel.getTempTradingPeerNodeAddress());
          sender.setUpdatedMultisigHex(request.getUpdatedMultisigHex());

          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
