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

package haveno.desktop.components;

import com.jfoenix.controls.JFXTextField;
import haveno.core.trade.HavenoUtils;
import haveno.core.util.coin.CoinFormatter;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;

import javax.annotation.Nullable;
import java.math.BigInteger;

public class BalanceTextField extends AnchorPane {

    private BigInteger targetAmount;
    private final JFXTextField textField;
    private final Effect fundedEffect = new DropShadow(BlurType.THREE_PASS_BOX, Color.GREEN, 4, 0.0, 0, 0);
    private final Effect notFundedEffect = new DropShadow(BlurType.THREE_PASS_BOX, Color.ORANGERED, 4, 0.0, 0, 0);
    private CoinFormatter formatter;
    @Nullable
    private BigInteger balance;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BalanceTextField(String label) {
        textField = new HavenoTextField();
        textField.setLabelFloat(true);
        textField.getStyleClass().add("label-float");
        textField.setPromptText(label);
        textField.setFocusTraversable(false);
        textField.setEditable(false);
        textField.setId("info-field");

        AnchorPane.setRightAnchor(textField, 0.0);
        AnchorPane.setLeftAnchor(textField, 0.0);

        getChildren().addAll(textField);
    }

    public void setFormatter(CoinFormatter formatter) {
        this.formatter = formatter;
    }

    public void setBalance(BigInteger balance) {
        this.balance = balance;

        updateBalance(balance);
    }

    public void setTargetAmount(BigInteger targetAmount) {
        this.targetAmount = targetAmount;

        if (this.balance != null)
            updateBalance(balance);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateBalance(BigInteger balance) {
        if (formatter != null)
            textField.setText(HavenoUtils.formatXmr(balance, true));

        //TODO: replace with new validation logic
//        if (targetAmount != null) {
//            if (balance.compareTo(targetAmount) >= 0)
//                textField.setEffect(fundedEffect);
//            else
//                textField.setEffect(notFundedEffect);
//        } else {
//            textField.setEffect(null);
//        }
    }
}
