package bisq.core.trade.protocol;

import bisq.core.trade.ArbitratorTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.DepositTxMessage;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ArbitratorSendsInitTradeRequestToMakerIfFromTaker;
import bisq.core.trade.protocol.tasks.ArbitratorProcessesReserveTx;
import bisq.core.trade.protocol.tasks.ProcessInitTradeRequest;
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
                  ArbitratorSendsInitTradeRequestToMakerIfFromTaker.class))
               // ArbitratorSendsInitMultisigRequestIfFromMaker
              .executeTasks();
  }

  @Override
  public void handleDepositTxMessage(DepositTxMessage message, NodeAddress taker, ErrorMessageHandler errorMessageHandler) {
    throw new RuntimeException("Not implemented");
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
