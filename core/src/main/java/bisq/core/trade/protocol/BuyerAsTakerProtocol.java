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


import bisq.core.offer.Offer;
import bisq.core.trade.BuyerAsTakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DelayedPayoutTxSignatureRequest;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.InputsForDepositTxResponse;
import bisq.core.trade.messages.MakerReadyToFundMultisigResponse;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigMessage;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.trade.protocol.tasks.VerifyPeersAccountAgeWitness;
import bisq.core.trade.protocol.tasks.buyer.BuyerFinalizesDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerProcessDelayedPayoutTxSignatureRequest;
import bisq.core.trade.protocol.tasks.buyer.BuyerSendsDelayedPayoutTxSignatureResponse;
import bisq.core.trade.protocol.tasks.buyer.BuyerSetupDepositTxListener;
import bisq.core.trade.protocol.tasks.buyer.BuyerSignsDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer.BuyerVerifiesPreparedDelayedPayoutTx;
import bisq.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSendsDepositTxMessage;
import bisq.core.trade.protocol.tasks.buyer_as_taker.BuyerAsTakerSignsDepositTx;
import bisq.core.trade.protocol.tasks.taker.FundMultisig;
import bisq.core.trade.protocol.tasks.taker.TakerCreateFeeTx;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesInputsForDepositTxResponse;
import bisq.core.trade.protocol.tasks.taker.TakerProcessesMakerDepositTxMessage;
import bisq.core.trade.protocol.tasks.taker.TakerPublishFeeTx;
import bisq.core.trade.protocol.tasks.taker.TakerReservesTradeFunds;
import bisq.core.trade.protocol.tasks.taker.TakerSendInitMultisigMessages;
import bisq.core.trade.protocol.tasks.taker.TakerSendReadyToFundMultisigRequest;
import bisq.core.trade.protocol.tasks.taker.TakerSendsInitTradeRequestToArbitrator;
import bisq.core.trade.protocol.tasks.taker.TakerSetupDepositTxsListener;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyAndSignContract;
import bisq.core.trade.protocol.tasks.taker.TakerVerifyMakerFeePayment;
import bisq.core.util.Validator;

import bisq.network.p2p.NodeAddress;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import java.math.BigInteger;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;



import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;
import monero.wallet.model.MoneroWalletListener;

// TODO (woodser): remove unused request handling
@Slf4j
public class BuyerAsTakerProtocol extends BuyerProtocol implements TakerProtocol {
    private ResultHandler takeOfferListener;
    private Timer initDepositTimer;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        super(trade);

        Offer offer = checkNotNull(trade.getOffer());
        processModel.getTradingPeer().setPubKeyRing(offer.getPubKeyRing());

       // TODO (woodser): setup deposit and payout listeners on construction for startup like before rebase?
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): this implementation is duplicated with SellerAsTakerProtocol
    @Override
    public void onTakeOffer() {
      System.out.println("onTakeOffer()");

      expect(phase(Trade.Phase.INIT)
          .with(TakerEvent.TAKE_OFFER)
          .from(trade.getTradingPeerNodeAddress()))
          .setup(tasks(
              ApplyFilter.class,
              TakerReservesTradeFunds.class,
              TakerSendsInitTradeRequestToArbitrator.class)
          .withTimeout(30))
          .executeTasks();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming messages Take offer process
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(InputsForDepositTxResponse message, NodeAddress peer) {
        expect(phase(Trade.Phase.INIT)
                .with(message)
                .from(peer))
                .setup(tasks(TakerProcessesInputsForDepositTxResponse.class,
                        ApplyFilter.class,
                        VerifyPeersAccountAgeWitness.class,
                        TakerVerifyAndSignContract.class,
                        TakerPublishFeeTx.class,
                        BuyerAsTakerSignsDepositTx.class,
                        BuyerSetupDepositTxListener.class,
                        BuyerAsTakerSendsDepositTxMessage.class)
                        .withTimeout(60))
                .executeTasks();
    }

    @Override
    protected void handle(DelayedPayoutTxSignatureRequest message, NodeAddress peer) {
        expect(phase(Trade.Phase.TAKER_FEE_PUBLISHED)
                .with(message)
                .from(peer))
                .setup(tasks(
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


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
        super.onTradeMessage(message, peer);

        if (message instanceof InputsForDepositTxResponse) {
            handle((InputsForDepositTxResponse) message, peer);
        }
    }

    @Override
    protected Class<? extends TradeTask> getVerifyPeersFeePaymentClass() {
        return TakerVerifyMakerFeePayment.class;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MakerProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // TODO (woodser): these methods are duplicated with SellerAsTakerProtocol due to single inheritance


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleMakerReadyToFundMultisigResponse(MakerReadyToFundMultisigResponse message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) {
      System.out.println("BuyerAsTakerProtocol.handleMakerReadyToFundMultisigResponse()");
      System.out.println("Maker is ready to fund multisig: " + message.isMakerReadyToFundMultisig());
      processModel.setTempTradingPeerNodeAddress(peer); // TODO: verify this
      if (processModel.isMultisigDepositInitiated()) throw new RuntimeException("Taker has already initiated multisig deposit.  This should not happen"); // TODO (woodser): proper error handling
      processModel.setTradeMessage(message);
      if (message.isMakerReadyToFundMultisig()) {
        createAndFundMultisig(message, takeOfferListener);
      } else if (trade.getTakerFeeTxId() == null && !trade.getState().equals(Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX)) { // TODO (woodser): use processModel.isTradeFeeTxInitiated() like check above to avoid timing issues with subsequent requests
        reserveTrade(message, takeOfferListener);
      }
    }

    private void reserveTrade(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
      System.out.println("BuyerAsTakerProtocol.reserveTrade()");

      // define wallet listener which initiates multisig deposit when trade fee tx unlocked
      // TODO (woodser): this needs run for reserved trades when client is opened
      // TODO (woodser): test initiating multisig when maker offline
      MoneroWallet wallet = processModel.getProvider().getXmrWalletService().getWallet();
      MoneroWalletListener fundMultisigListener = new MoneroWalletListener() {
        @Override
        public void onBalancesChanged(BigInteger newBalance, BigInteger newUnlockedBalance) {

          // get updated offer fee tx
          MoneroTxWallet feeTx = wallet.getTx(processModel.getTakeOfferFeeTxId());

          // check if tx is unlocked
          if (Boolean.FALSE.equals(feeTx.isLocked())) {
            System.out.println("TRADE FEE TX IS UNLOCKED!!!");

            // stop listening to wallet
            wallet.removeListener(this);

            // periodically request multisig deposit until successful
            Runnable requestMultisigDeposit = new Runnable() {
              @Override
              public void run() {
                if (!processModel.isMultisigDepositInitiated()) sendMakerReadyToFundMultisigRequest(message, handler);
                else initDepositTimer.stop();
              }
            };
            UserThread.execute(requestMultisigDeposit);
            initDepositTimer = UserThread.runPeriodically(requestMultisigDeposit, 60);
          }
        }
      };

      // run pipeline to publish trade fee tx
      expect(new FluentProtocol.Condition(trade))
        .setup(tasks(
            TakerCreateFeeTx.class,
            TakerVerifyMakerFeePayment.class,
            //TakerVerifyAndSignContract.class, // TODO (woodser): no... create taker fee tx, send to maker which creates contract, returns, then taker verifies and signs contract, then publishes taker fee tx
            TakerPublishFeeTx.class)  // TODO (woodser): need to notify maker/network of trade fee tx id to reserve trade?
            .using(new TradeTaskRunner(trade,
                () -> {
                  stopTimeout();
                  handleTaskRunnerSuccess(null, message);
                  if (handler != null) handler.handleResult();  // TODO (woodser): use handler to timeout initializing entire trade or remove use of handler and let gui indicate failure later?
                  wallet.addListener(fundMultisigListener);  // listen for trade fee tx to become available then initiate multisig deposit  // TODO: put in pipeline
                },
                errorMessage -> {
                    handleTaskRunnerFault(null, message, errorMessage);
                }))
            .withTimeout(30))
        .executeTasks();
    }

    private void sendMakerReadyToFundMultisigRequest(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
      System.out.println("TakerProtocolBase.sendMakerReadyToFundMultisigRequest()");
      expect(new FluentProtocol.Condition(trade))
        .setup(tasks(
            TakerVerifyMakerFeePayment.class,
            TakerSendReadyToFundMultisigRequest.class)
            .using(new TradeTaskRunner(trade,
                () -> {
                  stopTimeout();
                  handleTaskRunnerSuccess(null, message);
                },
                errorMessage -> {
                  handleTaskRunnerFault(null, message, errorMessage);
                }))
            .withTimeout(30))
        .executeTasks();
    }

    private void createAndFundMultisig(MakerReadyToFundMultisigResponse message, ResultHandler handler) {
      System.out.println("TakerProtocolBase.createAndFundMultisig()");
      expect(new FluentProtocol.Condition(trade))
          .setup(tasks(
                  TakerVerifyMakerFeePayment.class,
                  TakerVerifyAndSignContract.class,
                  TakerSendInitMultisigMessages.class)  // will receive MultisigMessage in response
                  .using(new TradeTaskRunner(trade,
                          () -> {
                            stopTimeout();
                            handleTaskRunnerSuccess(null, message);
                          },
                          errorMessage -> {
                              handleTaskRunnerFault(null, message, errorMessage);
                          }))
                  .withTimeout(30))
          .executeTasks();
    }

    @Override
    public void handleMultisigMessage(InitMultisigMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
      System.out.println("TakerProtocolBase.handleMultisigMessage()");
      Validator.checkTradeId(processModel.getOfferId(), message);
      processModel.setTradeMessage(message);
      expect(anyPhase(Trade.Phase.INIT, Trade.Phase.TAKER_FEE_PUBLISHED)
          .with(message)
          .from(sender))
          .setup(tasks(
              ProcessInitMultisigMessage.class)
              .using(new TradeTaskRunner(trade,
                  () -> {
                    System.out.println("handle multisig pipeline completed successfully!");
                    handleTaskRunnerSuccess(sender, message);
                    if (processModel.isMultisigSetupComplete() && !processModel.isMultisigDepositInitiated()) {
                      processModel.setMultisigDepositInitiated(true); // ensure only funding multisig one time
                      fundMultisig(message, takeOfferListener);
                    }
                  },
                  errorMessage -> {
                      System.out.println("error in handle multisig pipeline!!!: " + errorMessage);
                      errorMessageHandler.handleErrorMessage(errorMessage);
                      handleTaskRunnerFault(sender, message, errorMessage);
                      takeOfferListener.handleResult();
                  })))
          .executeTasks();
    }

    private void fundMultisig(InitMultisigMessage message, ResultHandler handler) {
      System.out.println("TakerProtocolBase.fundMultisig()");
      expect(new FluentProtocol.Condition(trade))
          .setup(tasks(
                  FundMultisig.class).  // will receive MultisigMessage in response
                  using(new TradeTaskRunner(trade,
                          () -> {
                            System.out.println("MULTISIG WALLET FUNDED!!!!");
                            stopTimeout();
                            handleTaskRunnerSuccess(null, message);
                          },
                          errorMessage -> {
                              handleTaskRunnerFault(null, message, errorMessage);
                          }))
                  .withTimeout(30))
          .executeTasks();
    }

    @Override
    public void handleDepositTxMessage(DepositTxMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
      System.out.println("TakerProtocolBase.handleDepositTxMessage()");
      processModel.setTradeMessage(message);
      expect(anyPhase(Trade.Phase.INIT, Trade.Phase.DEPOSIT_PUBLISHED)
          .with(message)
          .from(sender))
          .setup(tasks(
                  TakerProcessesMakerDepositTxMessage.class,
                  TakerSetupDepositTxsListener.class).
                  using(new TradeTaskRunner(trade,
                          () -> {
                            stopTimeout();
                            handleTaskRunnerSuccess(sender, message);
                          },
                          errorMessage -> {
                              errorMessageHandler.handleErrorMessage(errorMessage);
                              handleTaskRunnerFault(sender, message, errorMessage);
                          }))
                  .withTimeout(30))
          .executeTasks();
    }
}
