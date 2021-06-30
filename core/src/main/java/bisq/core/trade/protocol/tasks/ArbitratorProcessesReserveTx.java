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

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.taskrunner.TaskRunner;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.messages.SignOfferRequest;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.messages.InitTradeRequest;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.util.ParsingUtils;
import common.utils.JsonUtils;
import java.math.BigInteger;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroCheckTx;

/**
 * Arbitrator verifies reserve tx from maker or taker.
 * 
 * The maker reserve tx is only verified here if this arbitrator is not
 * the original offer signer and thus does not have the original reserve tx.
 */
@Slf4j
public class ArbitratorProcessesReserveTx extends TradeTask {
    @SuppressWarnings({"unused"})
    public ArbitratorProcessesReserveTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            Offer offer = trade.getOffer();
            InitTradeRequest request = (InitTradeRequest) processModel.getTradeMessage();
            checkNotNull(request);
            boolean isFromTaker = request.getSenderNodeAddress().equals(request.getTakerNodeAddress());
            
            // TODO (woodser): if signer online, should never be called by maker
            
            // process reserve tx with expected terms
            BigInteger tradeFee = ParsingUtils.coinToAtomicUnits(isFromTaker ? trade.getTakerFee() : offer.getMakerFee());
            BigInteger tradeAmount = ParsingUtils.coinToAtomicUnits(offer.getDirection() == OfferPayload.Direction.SELL ? offer.getAmount().add(offer.getSellerSecurityDeposit()) : offer.getBuyerSecurityDeposit());
            TradeUtils.processReserveTx(trade.getOffer(),
                    tradeFee,
                    tradeAmount,
                    request.getPayoutAddress(),
                    processModel.getXmrWalletService().getDaemon(),
                    processModel.getXmrWalletService().getWallet(),
                    request.getReserveTxHash(),
                    request.getReserveTxHex(),
                    request.getReserveTxKey());
            
            // save reserve tx to model
            TradingPeer trader = isFromTaker ? processModel.getTaker() : processModel.getMaker();
            trader.setReserveTxHash(request.getReserveTxHash());
            trader.setReserveTxHex(request.getReserveTxHex());
            trader.setReserveTxKey(request.getReserveTxKey());
            
            // persist trade
            processModel.getTradeManager().requestPersistence();
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
