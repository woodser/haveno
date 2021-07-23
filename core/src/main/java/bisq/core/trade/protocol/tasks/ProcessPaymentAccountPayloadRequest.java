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


import bisq.common.taskrunner.TaskRunner;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.messages.PaymentAccountPayloadRequest;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessPaymentAccountPayloadRequest extends TradeTask {
    
    @SuppressWarnings({"unused"})
    public ProcessPaymentAccountPayloadRequest(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // get peer's payment account payload
          PaymentAccountPayloadRequest request = (PaymentAccountPayloadRequest) processModel.getTradeMessage(); // TODO (woodser): verify request
          PaymentAccountPayload paymentAccountPayload = request.getPaymentAccountPayload();
          
          // verify hash of payment account payload
          byte[] peerPaymentAccountPayloadHash = trade instanceof MakerTrade ? trade.getContract().getTakerPaymentAccountPayloadHash() : trade.getContract().getMakerPaymentAccountPayloadHash();
          if (!Arrays.equals(paymentAccountPayload.getHash(), peerPaymentAccountPayloadHash)) throw new RuntimeException("Hash of peer's payment account payload does not match contract");
          
          // listen for deposit transactions
          // TODO (woodser): listen for deposit transaction
          
          // set trade state
          
          complete();
        } catch (Throwable t) {
          failed(t);
        }
    }
}
