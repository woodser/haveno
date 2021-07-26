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

package bisq.core.trade;

import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OfferPayload;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.Sig;
import bisq.common.util.Tuple2;
import bisq.common.util.Utilities;
import java.util.Objects;

public class TradeUtils {
    
    /**
     * Indicates if the given offer payload has a valid signature from the given arbitrator.
     * 
     * @param arbitrator is the possible original arbitrator
     * @param signedOfferPayload is a signed offer payload
     * @return true if the offer payload's signature is valid, false otherwise
     */
    public static boolean isSignatureValid(OfferPayload signedOfferPayload, Mediator arbitrator) {
        
        // remove arbitrator signature from signed payload
        String signature = signedOfferPayload.getArbitratorSignature();
        signedOfferPayload.setArbitratorSignature(null);
        
        // get unsigned offer payload as json string
        String unsignedOfferAsJson = Utilities.objectToJson(signedOfferPayload);
        
        // verify arbitrator signature
        boolean isValid = true;
        try {
            Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(),
                    unsignedOfferAsJson,
                    signature);
        } catch (Exception e) {
            isValid = false;
        }
        
        // replace signature
        signedOfferPayload.setArbitratorSignature(signature);
        
        // return result
        return isValid;
    }

    // Returns <MULTI_SIG, TRADE_PAYOUT> if both are AVAILABLE, otherwise null
    static Tuple2<String, String> getAvailableAddresses(Trade trade, XmrWalletService xmrWalletService,
                                                        KeyRing keyRing) {
        var addresses = getTradeAddresses(trade, xmrWalletService, keyRing);
        if (addresses == null)
            return null;

        if (xmrWalletService.getAvailableAddressEntries().stream()
                .noneMatch(e -> Objects.equals(e.getAddressString(), addresses.first)))
            return null;
        if (xmrWalletService.getAvailableAddressEntries().stream()
                .noneMatch(e -> Objects.equals(e.getAddressString(), addresses.second)))
            return null;

        return new Tuple2<>(addresses.first, addresses.second);
    }

    // Returns <MULTI_SIG, TRADE_PAYOUT> addresses as strings if they're known by the wallet
    public static Tuple2<String, String> getTradeAddresses(Trade trade, XmrWalletService xmrWalletService,
                                                           KeyRing keyRing) {
        var contract = trade.getContract();
        if (contract == null)
            return null;

        // TODO (woodser): xmr multisig does not use pub key
        throw new RuntimeException("need to replace btc multisig pub key with xmr");

        // Get multisig address
//        var isMyRoleBuyer = contract.isMyRoleBuyer(keyRing.getPubKeyRing());
//        var multiSigPubKey = isMyRoleBuyer ? contract.getBuyerMultiSigPubKey() : contract.getSellerMultiSigPubKey();
//        if (multiSigPubKey == null)
//            return null;
//        var multiSigPubKeyString = Utilities.bytesAsHexString(multiSigPubKey);
//        var multiSigAddress = xmrWalletService.getAddressEntryListAsImmutableList().stream()
//                .filter(e -> e.getKeyPair().getPublicKeyAsHex().equals(multiSigPubKeyString))
//                .findAny()
//                .orElse(null);
//        if (multiSigAddress == null)
//            return null;
//
//        // Get payout address
//        var payoutAddress = isMyRoleBuyer ?
//                contract.getBuyerPayoutAddressString() : contract.getSellerPayoutAddressString();
//        var payoutAddressEntry = xmrWalletService.getAddressEntryListAsImmutableList().stream()
//                .filter(e -> Objects.equals(e.getAddressString(), payoutAddress))
//                .findAny()
//                .orElse(null);
//        if (payoutAddressEntry == null)
//            return null;
//
//        return new Tuple2<>(multiSigAddress.getAddressString(), payoutAddress);
    }
}
