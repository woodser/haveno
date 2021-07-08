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
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.messages.InitTradeRequest;
import common.utils.JsonUtils;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.util.Tuple2;
import bisq.common.util.Utilities;
import java.math.BigInteger;
import java.util.Objects;
import monero.common.MoneroError;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroCheckTx;

/**
 * Collection of utilities for trading.
 */
public class TradeUtils {
    
    /**
     * Address to collect Haveno trade fees. TODO (woodser): move to config constants
     */
    public static String FEE_ADDRESS = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM";
    
    /**
     * Check if the arbitrator signature for an offer is valid.
     * 
     * @param arbitrator is the possible original arbitrator
     * @param signedOfferPayload is a signed offer payload
     * @return true if the arbitrator's signature is valid for the offer
     */
    public static boolean isArbitratorSignatureValid(OfferPayload signedOfferPayload, Mediator arbitrator) {
        
        // remove arbitrator signature from signed payload
        String signature = signedOfferPayload.getArbitratorSignature();
        signedOfferPayload.setArbitratorSignature(null);
        
        // get unsigned offer payload as json string
        String unsignedOfferAsJson = Utilities.objectToJson(signedOfferPayload);
        
        // verify arbitrator signature
        boolean isValid = true;
        try {
            isValid = Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(), // TODO (woodser): assign isValid
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
    
    /**
     * Check if the maker signature for a trade request is valid.
     * 
     * @param request is the trade request to check
     * @return true if the maker's signature is valid for the trade request
     */
    public static boolean isMakerSignatureValid(InitTradeRequest request, String signature, PubKeyRing makerPubKeyRing) {
        
        // re-create trade request with signed fields
        InitTradeRequest signedRequest = new InitTradeRequest(
                request.getTradeId(),
                request.getSenderNodeAddress(),
                request.getPubKeyRing(),
                request.getTradeAmount(),
                request.getTradePrice(),
                request.getTradeFee(),
                request.getAccountId(),
                request.getPaymentAccountId(),
                request.getUid(),
                request.getMessageVersion(),
                request.getAccountAgeWitnessSignatureOfOfferId(),
                request.getCurrentDate(),
                request.getMakerNodeAddress(),
                request.getTakerNodeAddress(),
                null,
                null,
                null,
                null,
                request.getPayoutAddress(),
                null
                );
        
        // get trade request as string
        String tradeRequestAsJson = Utilities.objectToJson(signedRequest);
        
        // verify maker signature
        try {
            return Sig.verify(makerPubKeyRing.getSignaturePubKey(),
                    tradeRequestAsJson,
                    signature);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Processes a reserve tx by submitting to a daemon to check for double spends
     * and verifying the trade fee, reserved deposit amount, and mining fee.
     * 
     * @param offer the offer to verify the reserve tx
     * @param fromMaker specifies if reserve tx is from the maker or taker
     * @param daemon is the Monero daemon to check for double spends
     * @param wallet is the Monero wallet to verify the reserve tx
     * @param txHash is the reserve tx hash
     * @param txHex is the reserve tx full hex
     * @param txKey is the reserve tx secret key
     */
    public static void processReserveTx(Offer offer, BigInteger tradeFee, BigInteger depositAmount, String returnAddress, MoneroDaemon daemon, MoneroWallet wallet, String txHash, String txHex, String txKey) {

        try {
            
            // get reserve tx from daemon
            MoneroTx tx = daemon.getTx(txHash); // TODO (woodser): return null if tx not found instead of throwing?
            
            // reserve tx must not be relayed
            if (tx.isRelayed()) throw new RuntimeException("Reserve tx must not be relayed");

        } catch (MoneroError e) {
            
            // submit reserve tx to daemon but do not relay
            MoneroSubmitTxResult result = daemon.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
            if (!result.isGood()) throw new RuntimeException("Failed to submit reserve tx to daemon: " + JsonUtils.serialize(result));
        }

        // verify trade fee
        String feeAddress = TradeUtils.FEE_ADDRESS;
        MoneroCheckTx check = wallet.checkTxKey(txHash, txKey, feeAddress);
        if (!check.isGood()) throw new RuntimeException("Invalid proof of trade fee");
        if (!check.getReceivedAmount().equals(tradeFee)) throw new RuntimeException("Reserved trade fee is incorrect amount, expected " + tradeFee + " but was " + check.getReceivedAmount());

        // verify mining fee
        BigInteger feeEstimate = daemon.getFeeEstimate().multiply(BigInteger.valueOf(txHex.length())); // TODO (woodser): fee estimates are too high, use more accurate estimate
        BigInteger feeThreshold = feeEstimate.multiply(BigInteger.valueOf(1l)).divide(BigInteger.valueOf(2l)); // must be at least 50% of estimated fee
        MoneroTx tx = daemon.getTx(txHash);
        if (tx.getFee().compareTo(feeThreshold) < 0) {
            throw new RuntimeException("Reserve tx mining fee is not enough, needed " + feeThreshold + " but was " + tx.getFee());
        }

        // verify deposit amount
        check = wallet.checkTxKey(txHash, txKey, returnAddress);
        if (!check.isGood()) throw new RuntimeException("Invalid proof of deposit amount");
        BigInteger depositThreshold = depositAmount.add(feeThreshold.multiply(BigInteger.valueOf(3l))); // prove reserve of at least deposit amount + (3 * min mining fee)
        if (check.getReceivedAmount().compareTo(depositThreshold) < 0) throw new RuntimeException("Reserve tx deposit amount is not enough, needed " + depositThreshold + " but was " + check.getReceivedAmount());
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
