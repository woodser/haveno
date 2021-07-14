package bisq.core.trade.protocol;

import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.InitMultisigMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitTradeRequestToMakerIfFromTaker;
import bisq.core.trade.protocol.tasks.ProcessInitMultisigMessage;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitMultisigMessagesIfFundsReserved;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
import bisq.core.util.Validator;
import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArbitratorProtocol extends DisputeProtocol {

  public ArbitratorProtocol(ArbitratorTrade trade) {
    super(trade);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Incoming messages
  ///////////////////////////////////////////////////////////////////////////////////////////

  public void handleInitTradeRequest(InitTradeRequest message, NodeAddress peer, ErrorMessageHandler errorMessageHandler) { // TODO (woodser): update impl to use errorMessageHandler
      processModel.setTradeMessage(message); // TODO (woodser): confirm these are null without being set
      //processModel.setTempTradingPeerNodeAddress(peer);
      expect(phase(Trade.Phase.INIT)
              .with(message)
              .from(peer))
              .setup(tasks(
                  ApplyFilter.class,
                  ProcessInitTradeRequest.class,
                  ArbitratorProcessesReserveTx.class,
                  ArbitratorSendsInitTradeRequestToMakerIfFromTaker.class,
                  ArbitratorSendsInitMultisigMessagesIfFundsReserved.class))
              .executeTasks();
  }
  
  @Override
  public void handleMultisigMessage(InitMultisigMessage message, NodeAddress sender, ErrorMessageHandler errorMessageHandler) {
    System.out.println("ArbitratorProtocol.handleMultisigMessage()");
    Validator.checkTradeId(processModel.getOfferId(), message);
    processModel.setTradeMessage(message);
    expect(anyPhase(Trade.Phase.INIT)
        .with(message)
        .from(sender))
        .setup(tasks(
            ProcessInitMultisigMessage.class)
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

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Message dispatcher
  ///////////////////////////////////////////////////////////////////////////////////////////

//  @Override
//  protected void onTradeMessage(TradeMessage message, NodeAddress peer) {
//    if (message instanceof InitTradeRequest) {
//      handleInitTradeRequest((InitTradeRequest) message, peer);
//    }
//  }
}
