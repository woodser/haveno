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

package haveno.desktop.components.paymentmethods;

import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.locale.Country;
import haveno.core.locale.Res;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.MpesaAccount;
import haveno.core.payment.PaymentAccount;
import haveno.core.payment.payload.MpesaAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.util.coin.CoinFormatter;
import haveno.core.util.validation.InputValidator;
import haveno.desktop.components.InputTextField;
import haveno.desktop.util.GUIUtil;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextField;
import static haveno.desktop.util.FormBuilder.addCompactTopLabelTextFieldWithCopyIcon;
import static haveno.desktop.util.FormBuilder.addInputTextField;

import haveno.common.util.Tuple2;

public class MpesaForm extends PaymentMethodForm {

    private final MpesaAccount mpesaAccount;
    private Country selectedCountry;

    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        addCompactTopLabelTextFieldWithCopyIcon(gridPane, ++gridRow, Res.get("payment.mobile"), ((MpesaAccountPayload) paymentAccountPayload).getMobileNr());
        return gridRow;
    }

    public MpesaForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, InputValidator inputValidator, GridPane gridPane, int gridRow, CoinFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, inputValidator, gridPane, gridRow, formatter);
        this.mpesaAccount = (MpesaAccount) paymentAccount;
    }

    @Override
    public void addFormForAddAccount() {
        gridRowFrom = gridRow + 1;

        InputTextField mobileNrInputTextField = addInputTextField(gridPane, ++gridRow, Res.get("payment.mobile"));
        mobileNrInputTextField.setValidator(inputValidator);
        mobileNrInputTextField.textProperty().addListener((ov, oldValue, newValue) -> {
            mpesaAccount.setMobileNr(newValue.trim());
            updateFromInputs();
        });

        Tuple2<ComboBox<TradeCurrency>, Integer> tuple = GUIUtil.addCountryTradeCurrencyComboBoxes(gridPane, gridRow, mpesaAccount.getSupportedCountries(), this::onCountrySelected);
        currencyComboBox = tuple.first;
        gridRow = tuple.second;

        addLimitations(false);
        addAccountNameTextFieldWithAutoFillToggleButton();
    }

    private void onCountrySelected(Country country) {
        selectedCountry = country;
        if (selectedCountry != null) {
            mpesaAccount.setCountry(selectedCountry);
            currencyComboBox.setDisable(true);
            currencyComboBox.getSelectionModel().select(mpesaAccount.getSingleTradeCurrency());
            updateFromInputs();
        }
    }

    @Override
    protected void autoFillNameTextField() {
        setAccountNameWithString(mpesaAccount.getMobileNr());
    }

    @Override
    public void addFormForEditAccount() {
        gridRowFrom = gridRow;
        addAccountNameTextFieldWithAutoFillToggleButton();
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.paymentMethod"), Res.get(paymentAccount.getPaymentMethod().getId()));
        TextField field = addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("payment.mobile"), mpesaAccount.getMobileNr()).second;
        field.setMouseTransparent(false);
        
        final String countryNameAndCode = mpesaAccount.getCountry() != null ? mpesaAccount.getCountry().name + " (" + mpesaAccount.getCountry().code + ")" : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.country"), countryNameAndCode);

        final String currencyNameAndCode = paymentAccount.getSingleTradeCurrency() != null ? paymentAccount.getSingleTradeCurrency().getNameAndCode() : "";
        addCompactTopLabelTextField(gridPane, ++gridRow, Res.get("shared.currency"), currencyNameAndCode);

        addLimitations(true);
    }

    @Override
    public void updateAllInputsValid() {
        allInputsValid.set(isAccountNameValid()
                && inputValidator.validate(mpesaAccount.getMobileNr()).isValid
                && paymentAccount.getTradeCurrencies().size() > 0);
    }
}
