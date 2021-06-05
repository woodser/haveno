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

package bisq.core.trade.protocol.tasks.taker;

import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.network.p2p.SendDirectMessageListener;

import bisq.common.app.Version;
import bisq.common.crypto.Sig;
import bisq.common.taskrunner.TaskRunner;

import com.google.common.base.Charsets;

import java.util.Date;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class TakerSendsInitTradeRequestToArbitrator extends TradeTask {

    @SuppressWarnings({"unused"})
    public TakerSendsInitTradeRequestToArbitrator(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // collect fields for request
            String offerId = processModel.getOffer().getId();
            XmrWalletService walletService = processModel.getProvider().getXmrWalletService();
            
            // taker signs offer using offer id as nonce to avoid challenge protocol
            final PaymentAccountPayload paymentAccountPayload = checkNotNull(processModel.getPaymentAccountPayload(trade), "processModel.getPaymentAccountPayload(trade) must not be null");
            byte[] sig = Sig.sign(processModel.getKeyRing().getSignatureKeyPair().getPrivate(), offerId.getBytes(Charsets.UTF_8));
            
            // get primary arbitrator
            Mediator arbitrator = processModel.getUser().getAcceptedMediatorByAddress(trade.getArbitratorNodeAddress());
            if (arbitrator == null) throw new RuntimeException("Cannot get arbitrator instance by trade id"); // TODO (woodser): null if arbitrator goes offline or never seen?
            
            // save pub keys
            processModel.getArbitrator().setPubKeyRing(arbitrator.getPubKeyRing());
            trade.setArbitratorPubKeyRing(processModel.getArbitrator().getPubKeyRing());
            trade.setMakerPubKeyRing(processModel.getTradingPeer().getPubKeyRing());
            
            // create request to initialize trade
            InitTradeRequest message = new InitTradeRequest(
                    offerId,
                    processModel.getMyNodeAddress(),
                    processModel.getPubKeyRing(),
                    trade.getTradeAmount().value,
                    trade.getTradePrice().getValue(),
                    trade.getTakerFee().getValue(),
                    paymentAccountPayload,
                    processModel.getAccountId(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    sig,
                    new Date().getTime(),
                    trade.getTakerNodeAddress(),
                    trade.getMakerNodeAddress(),
                    trade.getArbitratorNodeAddress(),
                    trade.getProcessModel().getReserveTx().getHash(),
                    trade.getProcessModel().getReserveTx().getFullHex(),
                    trade.getProcessModel().getReserveTx().getKey());

            // send request to arbitrator
            log.info("Send {} with offerId {} and uid {} to arbitrator {} with pub key ring", message.getClass().getSimpleName(), message.getTradeId(), message.getUid(), trade.getArbitratorNodeAddress(), trade.getArbitratorPubKeyRing());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitratorNodeAddress(),
                    trade.getArbitratorPubKeyRing(),
                    message,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}; uid={}", message.getClass().getSimpleName(), message.getTradeId(), message.getUid());
                        }
                        @Override // TODO (woodser): handle case where primary arbitrator is unavailable so use backup arbitrator, distinguish offline from bad ack
                        public void onFault(String errorMessage) {
                            log.error("Sending {} failed: uid={}; peer={}; error={}", message.getClass().getSimpleName(), message.getUid(), trade.getArbitratorNodeAddress(), errorMessage);
                            appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                            failed();
                        }
                    }
            );
        } catch (Throwable t) {
          failed(t);
        }
    }
}
