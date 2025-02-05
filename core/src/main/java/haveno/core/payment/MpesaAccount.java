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

package haveno.core.payment;

import haveno.core.api.model.PaymentAccountFormField;
import haveno.core.locale.Country;
import haveno.core.locale.CountryUtil;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;
import haveno.core.payment.payload.MpesaAccountPayload;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
public final class MpesaAccount extends CountryBasedPaymentAccount {

    protected static final List<PaymentAccountFormField.FieldId> INPUT_FIELD_IDS = List.of(
            PaymentAccountFormField.FieldId.ACCOUNT_NAME,
            PaymentAccountFormField.FieldId.MOBILE_NR,
            PaymentAccountFormField.FieldId.COUNTRY,
            PaymentAccountFormField.FieldId.ACCEPTED_COUNTRY_CODES,
            PaymentAccountFormField.FieldId.SALT
    );

    public static final List<TradeCurrency> SUPPORTED_CURRENCIES = CurrencyUtil.getAllFiatCurrencies();

    public MpesaAccount() {
        super(PaymentMethod.MPESA);
    }

    @Override
    protected PaymentAccountPayload createPayload() {
        return new MpesaAccountPayload(paymentMethod.getId(), id,
                getSupportedCountries());
    }

    public void setMobileNr(String mobileNr) {
        ((MpesaAccountPayload) paymentAccountPayload).setMobileNr(mobileNr);
    }

    public String getMobileNr() {
        return ((MpesaAccountPayload) paymentAccountPayload).getMobileNr();
    }

    public List<String> getAcceptedCountryCodes() {
        return ((MpesaAccountPayload) paymentAccountPayload).getAcceptedCountryCodes();
    }

    public void setAcceptedCountryCodes(List<String> acceptedCountryCodes) {
        ((MpesaAccountPayload) paymentAccountPayload).setAcceptedCountryCodes(acceptedCountryCodes);
    }

    public void addAcceptedCountry(String countryCode) {
        ((MpesaAccountPayload) paymentAccountPayload).addAcceptedCountry(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        ((MpesaAccountPayload) paymentAccountPayload).removeAcceptedCountry(countryCode);
    }

    @Override
    public void setCountry(Country country) {
        super.setCountry(country);
        String countryCode = country.code;
        TradeCurrency currency = CurrencyUtil.getCurrencyByCountryCode(countryCode);
        setSingleTradeCurrency(currency);
    }

    @Override
    public void onPersistChanges() {
        super.onPersistChanges();
        ((MpesaAccountPayload) paymentAccountPayload).onPersistChanges();
    }

    @Override
    public void revertChanges() {
        super.revertChanges();
        ((MpesaAccountPayload) paymentAccountPayload).revertChanges();
    }

    @Override
    public @NotNull List<PaymentAccountFormField.FieldId> getInputFieldIds() {
        return INPUT_FIELD_IDS;
    }

    @Override
    public @NotNull List<TradeCurrency> getSupportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    @Nullable
    public List<Country> getSupportedCountries() {
        return CountryUtil.getAllMpesaCountries();
    }

    @Override
    protected PaymentAccountFormField getEmptyFormField(PaymentAccountFormField.FieldId fieldId) {
        var field = super.getEmptyFormField(fieldId);
        switch (fieldId) {
        case ACCEPTED_COUNTRY_CODES:
            field.setSupportedCountries(CountryUtil.getAllMpesaCountries());
            break;
        default:
            // no action
        }
        return field;
    }
}
