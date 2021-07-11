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
import bisq.common.taskrunner.TaskRunner;
import bisq.core.offer.Offer;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.SellerTrade;
import bisq.core.trade.TakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.util.ParsingUtils;
import bisq.network.p2p.SendDirectMessageListener;
import java.math.BigInteger;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import monero.daemon.model.MoneroOutput;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;

@Slf4j
public class SendSignContractRequestAfterMultisig extends TradeTask {

    @SuppressWarnings({"unused"})
    public SendSignContractRequestAfterMultisig(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // skip if multisig wallet not complete
          if (!processModel.isMultisigSetupComplete()) return;
          
          // skip if deposit tx already created
          if (trade instanceof MakerTrade && trade.getMakerDepositTx() != null) throw new RuntimeException("Maker's deposit tx already created, this should not happen when following protocol.");
          if (trade instanceof TakerTrade && trade.getTakerDepositTx() != null) throw new RuntimeException("Taker's deposit tx already created, this should not happen when following protocol.");
          
          // flush reserve tx from pool
          trade.getXmrWalletService().getDaemon().flushTxPool(processModel.getReserveTxHash());
          
          // thaw reserved outputs
          System.out.println(processModel.getFrozenKeyImages());
          MoneroWallet wallet = trade.getXmrWalletService().getWallet();
          for (String frozenKeyImage : processModel.getFrozenKeyImages()) {
              wallet.thawOutput(frozenKeyImage);
          }
          
          // create deposit tx
          BigInteger tradeFee = ParsingUtils.coinToAtomicUnits(trade instanceof MakerTrade ? trade.getOffer().getMakerFee() : trade.getTakerFee());
          Offer offer = processModel.getOffer();
          BigInteger depositAmount = ParsingUtils.coinToAtomicUnits(trade instanceof SellerTrade ? offer.getAmount().add(offer.getSellerSecurityDeposit()) : offer.getBuyerSecurityDeposit());
          MoneroWallet multisigWallet = processModel.getProvider().getXmrWalletService().getOrCreateMultisigWallet(trade.getId());
          String multisigAddress = multisigWallet.getPrimaryAddress();
          System.out.println("DEPOSIT AMOUNT: " + depositAmount);
          MoneroTxWallet depositTx = TradeUtils.createDepositTx(trade.getXmrWalletService(), tradeFee, multisigAddress, depositAmount);
          
          // freeze deposit outputs
          for (MoneroOutput input : depositTx.getInputs()) {
              //wallet.freezeOutput(input.getKeyImage().getHex()); // TODO (woodser): actually freeze outputs!
          }
          
          // save process state
          processModel.setDepositTxXmr(depositTx);
          
          // create request to send deposit tx id to peer to sign contract
          SignContractRequest request = new SignContractRequest(
                  trade.getOffer().getId(),
                  processModel.getMyNodeAddress(),
                  processModel.getPubKeyRing(),
                  UUID.randomUUID().toString(),
                  Version.getP2PMessageVersion(),
                  new Date().getTime(),
                  processModel.getDepositTxXmr().getHash());

          // send request to trading peer
          System.out.println("Sending peer SignContractRequest...");
          processModel.getP2PService().sendEncryptedDirectMessage(trade.getTradingPeerNodeAddress(), trade.getTradingPeerPubKeyRing(), request, new SendDirectMessageListener() {
              @Override
              public void onArrived() {
                  log.info("{} arrived: trading peer={}; offerId={}; uid={}", request.getClass().getSimpleName(), trade.getTradingPeerNodeAddress(), trade.getId());
                  complete();
              }
              @Override
              public void onFault(String errorMessage) {
                  log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), trade.getTradingPeerNodeAddress(), trade.getId(), errorMessage);
                  appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                  failed();
              }
            });
        } catch (Throwable t) {
          failed(t);
        }
    }
}
