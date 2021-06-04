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

import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.placeoffer.PlaceOfferModel;
import bisq.core.support.dispute.mediation.mediator.Mediator;

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.crypto.Sig;
import bisq.common.taskrunner.Task;
import bisq.common.taskrunner.TaskRunner;
import bisq.common.util.Utilities;

public class MakerProcessesSignOfferResponse extends Task<PlaceOfferModel> {
    public MakerProcessesSignOfferResponse(TaskRunner<PlaceOfferModel> taskHandler, PlaceOfferModel model) {
        super(taskHandler, model);
    }

    @Override
    protected void run() {
        Offer offer = model.getOffer();
        try {
            runInterceptHook();
            
            // get signed offer payload
            OfferPayload signedOfferPayload = model.getSignOfferResponse().getSignedOfferPayload();
            
            // remove arbitrator signature from signed payload
            String signature = signedOfferPayload.getArbitratorSignature();
            signedOfferPayload.setArbitratorSignature(null);;
            
            // get unsigned offer payload as json string
            String offerPayloadAsJson = Utilities.objectToJson(offer.getOfferPayload());
            
            // verify arbitrator signature
            Mediator arbitrator = checkNotNull(model.getUser().getAcceptedMediatorByAddress(offer.getOfferPayload().getArbitratorNodeAddress()), "user.getAcceptedMediatorByAddress(mediatorNodeAddress) must not be null");
            Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(),
                    offerPayloadAsJson,
                    signature);
            
            // replace signature
            signedOfferPayload.setArbitratorSignature(signature);
            
            complete();
        } catch (Exception e) {
            offer.setErrorMessage("An error occurred.\n" +
                    "Error message:\n"
                    + e.getMessage());
            failed(e);
        }
    }
}
