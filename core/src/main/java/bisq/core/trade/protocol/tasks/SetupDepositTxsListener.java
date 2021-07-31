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
import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroOutputWallet;
import monero.wallet.model.MoneroWalletListener;

@Slf4j
public class SetupDepositTxsListener extends TradeTask {

    private Boolean makerDepositLocked; // null when unknown, true while locked, false when unlocked
    private Boolean takerDepositLocked;
    private MoneroWalletListener depositTxListener;

    @SuppressWarnings({ "unused" })
    public SetupDepositTxsListener(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // create listener for deposit transactions
            MoneroWallet multisigWallet = processModel.getXmrWalletService().getMultisigWallet(trade.getId());
            depositTxListener = processModel.getXmrWalletService().new HavenoWalletListener(new MoneroWalletListener() { // TODO (woodser): separate into own class file
                @Override
                public void onOutputReceived(MoneroOutputWallet output) {

                    // ignore if no longer listening
                    if (depositTxListener == null) return;

                    // TODO (woodser): remove this
                    if (output.getTx().isConfirmed() && (processModel.getMaker().getDepositTxHash().equals(output.getTx().getHash()) || processModel.getTaker().getDepositTxHash().equals(output.getTx().getHash()))) {
                        System.out.println("Deposit output for tx " + output.getTx().getHash() + " is confirmed at height " + output.getTx().getHeight());
                    }

                    // update locked state
                    if (output.getTx().getHash().equals(processModel.getMaker().getDepositTxHash())) makerDepositLocked = output.getTx().isLocked();
                    else if (output.getTx().getHash().equals(processModel.getTaker().getDepositTxHash())) takerDepositLocked = output.getTx().isLocked();

                    // deposit txs seen when both locked states seen
                    if (makerDepositLocked != null && takerDepositLocked != null) {
                        trade.setState(trade instanceof MakerTrade ? Trade.State.MAKER_SAW_DEPOSIT_TX_IN_NETWORK : Trade.State.TAKER_SAW_DEPOSIT_TX_IN_NETWORK);
                    }

                    // confirm trade and update ui when both deposits unlock
                    if (Boolean.FALSE.equals(makerDepositLocked) && Boolean.FALSE.equals(takerDepositLocked)) {
                        System.out.println("Multisig deposit txs unlocked!");
                        trade.applyDepositTxs(multisigWallet.getTx(processModel.getMaker().getDepositTxHash()), multisigWallet.getTx(processModel.getTaker().getDepositTxHash()));
                        multisigWallet.removeListener(depositTxListener); // remove listener when notified
                        depositTxListener = null; // prevent re-applying trade state in subsequent requests
                    }
                }
            });

            // register wallet listener
            multisigWallet.addListener(depositTxListener);
            complete();
        } catch (Throwable t) {
            failed(t);
        }
    }
}
