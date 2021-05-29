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

public class MakerSendsInitOfferRequest extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerSendsInitOfferRequest.class);

    @SuppressWarnings({"unused"})
    public MakerSendsInitOfferRequest(TaskRunner taskHandler, PlaceOfferModel model) {
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
        } catch (Throwable t) {
            offer.setErrorMessage("An error occurred.\n" +
                "Error message:\n"
                + t.getMessage());
            failed(t);
        }
    }
}
