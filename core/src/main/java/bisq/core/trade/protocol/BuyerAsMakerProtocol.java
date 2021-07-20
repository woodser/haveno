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

package bisq.core.trade.protocol;

import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DelayedPayoutTxSignatureRequest;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.messages.SignContractRequest;
import bisq.core.trade.messages.SignContractResponse;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigMessage;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.trade.protocol.tasks.ProcessSignContractRequest;
import bisq.core.trade.protocol.tasks.SendSignContractRequestAfterMultisig;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.buyer.BuyerFinalizesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendsDelayedPayoutTxSignatureResponse;
import bisq.core.trade.protocol.tasks.buyer.BuyerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerVerifiesPreparedDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.maker.MakerRemovesOpenOffer;
import bisq.core.trade.protocol.tasks.maker.MakerVerifyTakerFeePayment;
import bisq.core.util.Validator;

import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BuyerAsMakerProtocol extends BuyerProtocol implements MakerProtocol {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsMakerProtocol(BuyerAsMakerTrade trade) {
        super(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Handle take offer request
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): remove or ignore any unsupported requests

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void handle(DelayedPayoutTxSignatureRequest message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
                        MakerRemovesOpenOffer.class,
                        BuyerProcessDelayedPayoutTxSignatureRequest.class,
                        BuyerVerifiesPreparedDelayedPayoutTx.class,
                        BuyerSignsDelayedPayoutTx.class,
                        BuyerFinalizesDelayedPayoutTx.class,
                        BuyerSendsDelayedPayoutTxSignatureResponse.class)
                        .withTimeout(60))
                .executeTasks();
    }

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(DepositTxAndDelayedPayoutTxMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // User interaction
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which events we expect
    @Override
    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        super.onPaymentStarted(resultHandler, errorMessageHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message Payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We keep the handler here in as well to make it more transparent which messages we expect
    @Override
    protected void handle(PayoutTxPublishedMessage message, NodeAddress peer) {
        super.handle(message, peer);
    }


    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return MakerVerifyTakerFeePayment.class;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MakerProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): these methods are duplicated with SellerAsMakerProtocol due to single inheritance

    @Override
    public void handleInitTradeRequest(InitTradeRequest message,
                                       NodeAddress peer,
                                       ErrorMessageHandler errorMessageHandler) {
        expect(phase(Trade.Phase.INIT)
            .with(message)
            .from(peer))
            .setup(tasks(
                    ProcessInitTradeRequest.class,
                    //ApplyFilter.class, // TODO (woodser): these checks apply when maker signs availability request, but not here
                    //VerifyPeersAccountAgeWitness.class, // TODO (woodser): these checks apply after in multisig, means if rejected need to reimburse other's fee
                    //MakerSendsInitTradeRequestIfUnreserved.class, // TODO (woodser): implement this
                    MakerRemovesOpenOffer.class).
                    using(new TradeTaskRunner(trade,
                            () -> {
                              stopTimeout();
                              handleTaskRunnerSuccess(peer, message);
                            },
                            errorMessage -> {
                                errorMessageHandler.handleErrorMessage(errorMessage);
                                handleTaskRunnerFault(peer, message, errorMessage);
                            }))
                    .withTimeout(30))
            .executeTasks();
    }
    
    @Override
    public void handleMultisigMessage(InitMultisigMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
      System.out.println("BuyerAsMakerProtocol.handleMultisigMessage()");
      Validator.checkTradeId(processModel.getOfferId(), message);
      processModel.setTradeMessage(message);
      expect(anyPhase(Trade.Phase.INIT)
          .with(message)
          .from(sender))
          .setup(tasks(
                  ProcessInitMultisigMessage.class,
                  SendSignContractRequestAfterMultisig.class)
              .using(new TradeTaskRunner(trade,
                  () -> {
                    handleTaskRunnerSuccess(sender, message);
                  },
                  errorMessage -> {
                      errorMessageHandler.handleErrorMessage(errorMessage);
                      handleTaskRunnerFault(sender, message, errorMessage);
                  })))
          .executeTasks();
    }
    
    @Override
    public void handleSignContractRequest(SignContractRequest message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("BuyerAsMakerProtocol.handleSignContractRequest()");
        Validator.checkTradeId(processModel.getOfferId(), message);
        processModel.setTradeMessage(message);
        expect(anyPhase(Trade.Phase.INIT)
            .with(message)
            .from(sender))
            .setup(tasks(
                    // TODO (woodser): validate request
                    ProcessSignContractRequest.class)
                .using(new TradeTaskRunner(trade,
                    () -> {
                      handleTaskRunnerSuccess(sender, message);
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, message, errorMessage);
                    })))
            .executeTasks();
    }

    @Override
    public void handleSignContractResponse(SignContractResponse message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
        System.out.println("BuyerAsMakerProtocol.handleSignContractResponse()");
        Validator.checkTradeId(processModel.getOfferId(), message);
        processModel.setTradeMessage(message);
        expect(anyPhase(Trade.Phase.INIT)
            .with(message)
            .from(sender))
            .setup(tasks()
                    //VerifySignedContract.class)
                    //ProcessSignContractRequest.class)
                .using(new TradeTaskRunner(trade,
                    () -> {
                      handleTaskRunnerSuccess(sender, message);
                    },
                    errorMessage -> {
                        errorMessageHandler.handleErrorMessage(errorMessage);
                        handleTaskRunnerFault(sender, message, errorMessage);
                    })))
            .executeTasks();
    }
}
