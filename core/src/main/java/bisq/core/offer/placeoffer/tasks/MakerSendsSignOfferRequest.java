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

import bisq.common.app.Version;
import bisq.common.crypto.PubKeyRing;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.Offer;
import bisq.core.offer.availability.DisputeAgentSelection;
import bisq.core.offer.messages.SignOfferRequest;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendDirectMessageListener;
import java.util.Date;
import java.util.UUID;
import monero.wallet.MoneroWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MakerSendsSignOfferRequest extends Task<PlaceOfferModel> {
    private static final Logger log = LoggerFactory.getLogger(MakerSendsSignOfferRequest.class);

    @SuppressWarnings({"unused"})
    public MakerSendsSignOfferRequest(TaskRunner taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {

        Offer offer = model.getOffer();

        try {
            runInterceptHook();

            // select signing arbitrator
            Mediator arbitrator = DisputeAgentSelection.getLeastUsedArbitrator(model.getTradeStatisticsManager(), model.getMediatorManager()); // TODO (woodser): using mediator manager for arbitrators
            model.setArbitrator(arbitrator);
            
            // create request for arbitrator to sign offer
            SignOfferRequest request = new SignOfferRequest(
                    model.getOffer().getId(),
                    P2PService.getMyNodeAddress(),
                    model.getKeyRing().getPubKeyRing(),
                    model.getUser().getAccountId(),
                    offer.getOfferPayload(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    new Date().getTime(),
                    model.getReserveTx().getHash(),
                    model.getReserveTx().getFullHex(),
                    model.getReserveTx().getKey(),
                    model.getXmrWalletService().getWallet().getPrimaryAddress());

            // send request
            model.getP2PService().sendEncryptedDirectMessage(arbitrator.getNodeAddress(), arbitrator.getPubKeyRing(), request, new SendDirectMessageListener() {
                @Override
                public void onArrived() {
                    log.info("{} arrived: arbitrator={}; offerId={}; uid={}", request.getClass().getSimpleName(), arbitrator.getNodeAddress(), offer.getId());
                    complete();
                }
                @Override
                public void onFault(String errorMessage) {
                    log.error("Sending {} failed: uid={}; peer={}; error={}", request.getClass().getSimpleName(), arbitrator.getNodeAddress(), offer.getId(), errorMessage);
                    appendToErrorMessage("Sending message failed: message=" + request + "\nerrorMessage=" + errorMessage);
                    failed();
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
