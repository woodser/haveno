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

import bisq.common.UserThread;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.util.ParsingUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import monero.daemon.model.MoneroOutput;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MakerReservesTradeFunds extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerReservesTradeFunds.class);

    @SuppressWarnings({"unused"})
    public MakerReservesTradeFunds(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();
        String offerId = model.getOffer().getId();
        XmrWalletService walletService = model.getXmrWalletService();
        MoneroWallet wallet = model.getXmrWalletService().getWallet();

        try {
            runInterceptHook();
            
            // collect fields for reserve transaction
            String returnAddress = wallet.getPrimaryAddress();
            String feeReceiver = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM"; // TODO (woodser): don't hardcode
            BigInteger makerFee = ParsingUtils.satoshisToXmrAtomicUnits(offer.getMakerFee().value);
            BigInteger reservedFundsForOffer = ParsingUtils.satoshisToXmrAtomicUnits(model.getReservedFundsForOffer().value);
            
            // create transaction to reserve outputs for trade
            MoneroTxWallet prepareTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(feeReceiver, makerFee)
                    .addDestination(returnAddress, reservedFundsForOffer));

            // reserve additional funds to account for fluctuations in mining fee
            BigInteger extraMiningFee = prepareTx.getFee().multiply(BigInteger.valueOf(2l)); // add twice the mining fee
            final MoneroTxWallet reserveTx = wallet.createTx(new MoneroTxConfig()
                    .setAccountIndex(0)
                    .addDestination(feeReceiver, makerFee)
                    .addDestination(returnAddress, reservedFundsForOffer.add(extraMiningFee)));

            // freeze trade funds
            List<String> inputKeyImages = new ArrayList<String>();
            for (MoneroOutput input : reserveTx.getInputs()) {
                inputKeyImages.add(input.getKeyImage().getHex());
                //wallet.freezeOutput(input.getKeyImage().getHex()); // TODO: actually freeze funds!
            }
            
            // we delay one render frame to be sure we don't get called before the method call has
            // returned (tradeFeeTx would be null in that case)
            UserThread.execute(() -> {
                if (!completed) {
                    model.setReserveTx(reserveTx);
                    offer.setOfferFeePaymentTxId(reserveTx.getHash());
                    offer.setState(Offer.State.OFFER_FEE_RESERVED);
                    complete();
                } else {
                    log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
                }
            });
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
