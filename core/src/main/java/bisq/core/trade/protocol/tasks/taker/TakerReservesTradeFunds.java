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
import bisq.core.btc.model.XmrAddressEntry;
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
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

public class TakerReservesTradeFunds extends TradeTask {

    public TakerReservesTradeFunds(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {

        MoneroWallet wallet = model.getXmrWalletService().getWallet();
        MoneroDaemon daemon = model.getXmrWalletService().getDaemon();

        try {
            runInterceptHook();
            
            // collect fields for reserve transaction
            BigInteger takerFee = ParsingUtils.coinToAtomicUnits(trade.getTakerFee());
            BigInteger reservedFundsForOffer = ParsingUtils.centinerosToAtomicUnits(processModel.getFundsNeededForTradeAsLong());
            String returnAddress = model.getXmrWalletService().getAddressEntry(trade.getOffer().getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString();
            
            // create transaction to reserve outputs for trade
            MoneroTxWallet prepareTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, takerFee)
                    .addDestination(returnAddress, reservedFundsForOffer));

            // reserve additional funds to account for fluctuations in mining fee
            BigInteger extraMiningFee = prepareTx.getFee().multiply(BigInteger.valueOf(3l)); // add thrice the mining fee
            MoneroTxWallet reserveTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, takerFee)
                    .addDestination(returnAddress, reservedFundsForOffer.add(extraMiningFee)));
            
            // freeze trade funds
            List<String> inputKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) {
                inputKeyImages.add(input.getKeyImage().getHex());
                //wallet.freezeOutput(input.getKeyImage().getHex()); // TODO: actually freeze funds!
            }
            
            // submit tx to daemon but do not relay
            MoneroSubmitTxResult result = daemon.submitTxHex(reserveTx.getFullHex(), true);
            if (!result.isGood()) throw new RuntimeException("Failed to submit reserve tx to daemon: " + JsonUtils.serialize(result));
            
            // save process state
            processModel.setReserveTx(reserveTx);
            complete();
        } catch (Throwable t) {
            trade.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
