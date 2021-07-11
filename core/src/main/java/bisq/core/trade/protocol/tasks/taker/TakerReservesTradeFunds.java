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

import bisq.common.taskrunner.TaskRunner;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeUtils;
import bisq.core.trade.protocol.tasks.TradeTask;
import bisq.core.util.ParsingUtils;
import common.utils.JsonUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroOutput;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxWallet;

public class TakerReservesTradeFunds extends TradeTask {

    public TakerReservesTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            
            // create transaction to reserve trade
            BigInteger takerFee = ParsingUtils.coinToAtomicUnits(trade.getTakerFee());
            BigInteger depositAmount = ParsingUtils.centinerosToAtomicUnits(processModel.getFundsNeededForTradeAsLong());
            MoneroTxWallet reserveTx = TradeUtils.createReserveTx(model.getXmrWalletService(), trade.getId(), takerFee, depositAmount);
            
            // freeze trade funds
            List<String> frozenKeyImages = new ArrayList<String>();
            MoneroWallet wallet = model.getXmrWalletService().getWallet();
            for (MoneroOutput input : reserveTx.getInputs()) {
                frozenKeyImages.add(input.getKeyImage().getHex());
                wallet.freezeOutput(input.getKeyImage().getHex());
            }
            
            // submit tx to daemon but do not relay
            MoneroDaemon daemon = model.getXmrWalletService().getDaemon();
            MoneroSubmitTxResult result = daemon.submitTxHex(reserveTx.getFullHex(), true);
            if (!result.isGood()) throw new RuntimeException("Failed to submit reserve tx to daemon: " + JsonUtils.serialize(result));
            
            // save process state
            // TODO (woodser): persist
            processModel.setReserveTx(reserveTx);
            processModel.setReserveTxHash(reserveTx.getHash());
            processModel.setFrozenKeyImages(frozenKeyImages);
            trade.setTakerFeeTxId(reserveTx.getHash());
            //trade.setState(Trade.State.TAKER_PUBLISHED_TAKER_FEE_TX); // TODO (woodser): fee tx is not broadcast separate, update states
            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
