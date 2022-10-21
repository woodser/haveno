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
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerProcessFirstConfirmationMessage extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public BuyerProcessFirstConfirmationMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    // TODO: redo this to work from arbitrator and seller

    @Override
    protected void run() {
        try {
          runInterceptHook();

          if (true) throw new RuntimeException("Not implemented");

          // update peer node address if not from arbitrator
          if (!processModel.getTempTradingPeerNodeAddress().equals(trade.getArbitrator().getNodeAddress())) {
              trade.getTradingPeer().setNodeAddress(processModel.getTempTradingPeerNodeAddress());
          }

          // decrypt peer's payment account payload
          FirstConfirmationMessage request = (FirstConfirmationMessage) processModel.getTradeMessage();
          if (trade.getTradingPeer().getPaymentAccountPayload() == null) {
              trade.decryptPeersPaymentAccountPayload(request.getSellerPaymentAccountKey());
          }

          // store updated multisig hex for processing on payment sent
          if (request.getUpdatedMultisigHex() != null) trade.getTradingPeer().setUpdatedMultisigHex(request.getUpdatedMultisigHex());

          // persist and complete
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
