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


import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;
import bisq.core.trade.Contract;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.TradingPeer;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessSignContractRequest extends TradeTask {

    @SuppressWarnings({"unused"})
    public ProcessSignContractRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // extract fields from request
          SignContractRequest request = (SignContractRequest) processModel.getTradeMessage();
          TradingPeer peer;
          if (trade instanceof MakerTrade) {
              trade.setTakerDepositTxId(request.getDepositTxHash());
              peer = processModel.getTaker();
          } else {
              trade.setMakerDepositTxId(request.getDepositTxHash());
              peer = processModel.getMaker();
          }
          peer.setAccountId(request.getAccountId());
          peer.setPaymentAccountPayloadHash(request.getPaymentAccountPayloadHash());
          peer.setPayoutAddressString(request.getPayoutAddress());
          
          // create and sign contract
          Contract contract = TradeUtils.createContract(trade);
          String contractAsJson = Utilities.objectToJson(contract);
          String signature = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), contractAsJson);

          // save contract and signature
          trade.setContract(contract);
          trade.setContractAsJson(contractAsJson);
          trade.setTakerContractSignature(signature);
          
          // create response with contract signature
          SignContractResponse response = new SignContractResponse(
                  trade.getOffer().getId(),
                  processModel.getMyNodeAddress(),
                  processModel.getPubKeyRing(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  new Date().getTime(),
                  signature);

          // send response to trading peer
          processModel.getP2PService().sendEncryptedDirectMessage(trade.getTradingPeerNodeAddress(), trade.getTradingPeerPubKeyRing(), response, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", response.getClass().getSimpleName(), trade.getTradingPeerNodeAddress(), trade.getId());
                  complete();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", response.getClass().getSimpleName(), trade.getTradingPeerNodeAddress(), trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + response + "\nerrorMessage=" + errorMessage);
                  failed();
              }
            });
        } catch (Throwable t) {
          failed(t);
        }
    }
}
