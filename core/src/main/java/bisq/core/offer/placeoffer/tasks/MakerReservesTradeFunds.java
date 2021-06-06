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

package bisq.core.offer.placeoffer.tasks;

import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.offer.Offer;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.trade.TradeUtils;
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

public class MakerReservesTradeFunds extends Task<PlaceOfferModel> {

    public MakerReservesTradeFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();
        MoneroWallet wallet = model.getXmrWalletService().getWallet();
        MoneroDaemon daemon = model.getXmrWalletService().getDaemon();

        try {
            runInterceptHook();
            
            // collect fields for reserve transaction
            BigInteger makerFee = ParsingUtils.coinToAtomicUnits(offer.getMakerFee());
            BigInteger reservedFundsForOffer = ParsingUtils.coinToAtomicUnits(model.getReservedFundsForOffer());
            String payoutAdddress = model.getXmrWalletService().getNewAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).getAddressString(); // reserve new payout address
            
            // create transaction to reserve outputs for trade
            MoneroTxWallet prepareTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, makerFee)
                    .addDestination(payoutAdddress, reservedFundsForOffer));

            // reserve additional funds to account for fluctuations in mining fee
            BigInteger extraMiningFee = prepareTx.getFee().multiply(BigInteger.valueOf(3l)); // add thrice the mining fee
            MoneroTxWallet reserveTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(TradeUtils.FEE_ADDRESS, makerFee)
                    .addDestination(payoutAdddress, reservedFundsForOffer.add(extraMiningFee)));
            
            // freeze trade funds
            List<String> inputKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) {
                inputKeyImages.add(input.getKeyImage().getHex());
                //wallet.freezeOutput(input.getKeyImage().getHex()); // TODO: actually freeze funds!
            }
            
            // submit reserve tx to daemon but do not relay
            MoneroSubmitTxResult result = daemon.submitTxHex(reserveTx.getFullHex(), true);
            if (!result.isGood()) throw new RuntimeException("Failed to submit reserve tx to daemon: " + JsonUtils.serialize(result));
            
            // save offer state
            model.setReserveTx(reserveTx);
            offer.setOfferFeePaymentTxId(reserveTx.getHash());
            offer.setState(Offer.State.OFFER_FEE_RESERVED);
            complete();
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
