/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.desktop.main.overlays.windows;

import com.google.inject.Inject;
import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.ThreadUtils;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.provider.price.PriceFeedService;
import haveno.core.trade.HavenoUtils;
import haveno.core.user.Preferences;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.main.funds.transactions.TransactionsListItem;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.util.Accessibility;
import haveno.desktop.util.GUIUtil;
import haveno.desktop.util.GlyphsDude;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import lombok.extern.slf4j.Slf4j;
import monero.wallet.model.MoneroTxWallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Details of a wallet transaction, shown as a sent or received hero amount. */
@Slf4j
public class TxDetailsWindow extends TxHeroWindow<TxDetailsWindow> {

    private final XmrWalletService xmrWalletService;
    private final Preferences preferences;
    private final PriceFeedService priceFeedService;
    private TransactionsListItem item;
    private Runnable memoChangedHandler;
    private TextArea memoArea;
    private String memoBeforeEdit;

    @Inject
    public TxDetailsWindow(XmrWalletService xmrWalletService, Preferences preferences, PriceFeedService priceFeedService) {
        this.xmrWalletService = xmrWalletService;
        this.preferences = preferences;
        this.priceFeedService = priceFeedService;
    }

    public void show(TransactionsListItem item, Runnable memoChangedHandler) {
        this.item = item;
        this.memoChangedHandler = memoChangedHandler;
        showHeroWindow();
    }

    @Override
    protected void addContent() {
        MoneroTxWallet tx = item.getTx();
        boolean isOutgoing = tx.getOutgoingTransfer() != null;
        BigInteger amount = isOutgoing ? tx.getOutgoingAmount() : tx.getIncomingAmount();
        String txKey = null;
        if (isOutgoing) {
            try {
                synchronized (xmrWalletService.getWalletLock()) {
                    txKey = xmrWalletService.getWallet().getTxKey(tx.getHash());
                }
            } catch (Exception e) {
                // TODO (monero-java): wallet.getTxKey() should return null if key does not exist instead of throwing exception
            }
        }

        List<Node> groups = new ArrayList<>();
        groups.add(detailRow(Res.get("shared.dateTime"), selectableValue(item.getDateString())));
        groups.add(sheetGroup(Res.get(isOutgoing ? "txDetailsWindow.sentTo" : "txDetailsWindow.receivedWith"),
                selectableLabel(item.getAddressString(), "confirm-send-address"), copyIcon(item.getAddressString())));
        if (isOutgoing) groups.add(detailRow(Res.get("funds.withdrawal.confirm.networkFee"), selectableValue(HavenoUtils.formatXmr(tx.getFee(), true))));
        groups.add(memoGroup());
        if (txKey != null && !txKey.isEmpty()) groups.add(sheetGroup(Res.get("txDetailsWindow.txKey"), selectableLabel(txKey, "confirm-send-address"), copyIcon(txKey)));
        groups.add(txIdGroup(tx.getHash(), xmrWalletService, preferences));

        String fiat = GUIUtil.getFiatText(amount, priceFeedService, preferences);
        addHeroContent(
                isOutgoing
                        ? hero(MaterialDesignIcon.SEND, "confirm-send-icon", Res.get("funds.withdrawal.sent.headline"), amount, fiat)
                        : hero(MaterialDesignIcon.ARROW_DOWN, "tx-sent-icon", Res.get("txDetailsWindow.received"), amount, fiat),
                sheet(groups.toArray(new Node[0])));
    }

    @Override
    protected void onHidden() {
        super.onHidden();
        if (memoArea.isEditable()) endMemoEdit();
    }

    // memo group editable in place via its pencil icon, saved when editing ends
    private Node memoGroup() {
        TextArea area = selectableLabel(getMemo(item.getTxId()), "confirm-send-row-value");
        area.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && area == memoArea && area.isEditable()) endMemoEdit();
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && area.isEditable()) {
                e.consume();
                actionButton.requestFocus(); // ends the edit on focus loss
            }
        });
        memoArea = area;

        Label editIcon = new Label();
        editIcon.setGraphic(GlyphsDude.createIcon(MaterialDesignIcon.PENCIL, "1.1em"));
        editIcon.getStyleClass().add("confirm-send-copy");
        editIcon.setCursor(Cursor.HAND);
        editIcon.setTooltip(new Tooltip(Res.get("shared.edit")));
        editIcon.setOnMouseClicked(e -> startMemoEdit());
        Accessibility.asButton(editIcon, Res.get("shared.edit"));
        return sheetGroup(Res.get("funds.withdrawal.sent.note"), area, editIcon, copyIcon(area::getText));
    }

    private void startMemoEdit() {
        if (memoArea.isEditable()) return;
        memoBeforeEdit = memoArea.getText();
        memoArea.getStyleClass().add("editing");
        memoArea.setEditable(true);
        memoArea.requestFocus();
        memoArea.end();
    }

    private void endMemoEdit() {
        memoArea.getStyleClass().remove("editing");
        memoArea.setEditable(false);
        String txId = item.getTxId();
        if (memoArea.getText().equals(memoBeforeEdit)) {
            memoArea.setText(getMemo(txId)); // a save may have completed during the edit
            return;
        }
        String memo = memoArea.getText().trim();
        memoArea.setText(memo);
        Runnable handler = memoChangedHandler;
        ThreadUtils.execute(() -> {
            try {
                xmrWalletService.setTxNote(txId, memo);
            } catch (Exception e) {
                log.warn("Error setting memo for tx {}: {}", txId, e.getMessage());
                UserThread.execute(() -> new Popup().warning(e.getMessage()).show());
            }

            // reconcile with the wallet's note whether or not the save succeeded
            String note = getMemo(txId);
            UserThread.execute(() -> {
                if (txId.equals(item.getTxId()) && !memoArea.isEditable()) memoArea.setText(note);
                if (handler != null) handler.run();
            });
        }, TxDetailsWindow.class.getSimpleName());
    }

    private String getMemo(String txId) {
        MoneroTxWallet tx = xmrWalletService.getTx(txId);
        return tx == null || tx.getNote() == null ? "" : tx.getNote();
    }
}
