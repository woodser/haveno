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


import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.TradingPeer;
import common.utils.GenUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessSignContractResponse extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessSignContractResponse(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // wait until contract is available from peer's sign contract request
          // TODO (woodser): this will loop if peer disappears; use proper notification
          while (trade.getContract() == null) {
              GenUtils.waitFor(250);
          }
          
          // verify contract signature
          String contractAsJson = trade.getContractAsJson();
          SignContractResponse response = (SignContractResponse) processModel.getTradeMessage(); // TODO (woodser): verify response
          String signature = response.getContractSignature();
          
          // get peer info
          // TODO (woodser): make these utilities / refactor model
          TradingPeer peer;
          PubKeyRing peerPubKeyRing;
          if (trade.getArbitratorNodeAddress().equals(response.getSenderNodeAddress())) {
              peer = processModel.getArbitrator();
              peerPubKeyRing = trade.getArbitratorPubKeyRing();
          } else if (trade.getMakerNodeAddress().equals(response.getSenderNodeAddress())) {
              peer = processModel.getMaker();
              peerPubKeyRing = trade.getMakerPubKeyRing();
          } else if (trade.getTakerNodeAddress().equals(response.getSenderNodeAddress())) {
              peer = processModel.getTaker();
              peerPubKeyRing = trade.getTakerPubKeyRing();
          } else throw new RuntimeException(response.getClass().getSimpleName() + " is not from maker, taker, or arbitrator");
          
          // verify signature
          boolean isValid = Sig.verify(peerPubKeyRing.getSignaturePubKey(),
                  contractAsJson,
                  signature);
          if (!isValid) throw new RuntimeException("Peer's contract signature is invalid");
          
          // set peer's signature
          peer.setContractSignature(signature);
          
          // send deposit request when all contract signatures received
          if (processModel.getArbitrator().getContractSignature() != null && processModel.getMaker().getContractSignature() != null && processModel.getTaker().getContractSignature() != null) {
              log.error("Ready to send deposit request");
          }
          
          // save trade state
          processModel.getTradeManager().requestPersistence();
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
