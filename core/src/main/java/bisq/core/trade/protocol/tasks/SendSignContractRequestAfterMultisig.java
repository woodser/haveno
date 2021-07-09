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

import bisq.core.trade.MakerTrade;
import bisq.core.trade.TakerTrade;
import bisq.core.trade.Trade;

import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendSignContractRequestAfterMultisig extends TradeTask {

    @SuppressWarnings({"unused"})
    public SendSignContractRequestAfterMultisig(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
          runInterceptHook();
          
          // skip if multisig wallet not complete
          if (!processModel.isMultisigSetupComplete()) return;
          
          // skip if deposit tx already created
          if (trade instanceof MakerTrade && trade.getMakerDepositTx() != null) return;
          if (trade instanceof TakerTrade && trade.getTakerDepositTx() != null) return;
          
          // create deposit tx
          
          // send deposit tx id to peer to sign contract
          
          throw new RuntimeException("Not yet implemented");
        } catch (Throwable t) {
          failed(t);
        }
    }
}
