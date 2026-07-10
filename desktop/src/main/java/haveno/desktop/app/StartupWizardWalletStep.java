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

package haveno.desktop.app;

import de.jensd.fx.glyphs.materialdesignicons.MaterialDesignIcon;
import haveno.common.UserThread;
import haveno.core.locale.Res;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.components.AutoTooltipLabel;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Wizard step to create a new Monero wallet (default) or import an existing one from its seed
 * phrase with an optional restore height. The import is applied when the main wallet is first
 * created after connecting to the network. Skipped on a quick start.
 */
@Slf4j
public class StartupWizardWalletStep implements StartupWizard.Step {

    private static final double FIELD_WIDTH = 620;
    private static final double CARD_GAP = 20;
    private static final int SEED_WORD_COUNT = 25;

    private final BooleanSupplier quickStart;
    private final Supplier<XmrWalletService> xmrWalletService;
    private final VBox content;
    private final StartupWizard.ChoiceCard createCard, importCard;
    private final TextArea seedTextArea;
    private final TextField restoreHeightField;
    private final Label statusLabel = new AutoTooltipLabel();

    public StartupWizardWalletStep(BooleanSupplier quickStart, Supplier<XmrWalletService> xmrWalletService) {
        this.quickStart = quickStart;
        this.xmrWalletService = xmrWalletService;

        createCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.PLUS_CIRCLE_OUTLINE,
                Res.get("startupWizard.wallet.create"),
                null,
                Res.get("startupWizard.wallet.create.body"));
        importCard = new StartupWizard.ChoiceCard(MaterialDesignIcon.KEY_VARIANT,
                Res.get("startupWizard.wallet.import"),
                null,
                Res.get("startupWizard.wallet.import.body"));

        double cardWidth = (StartupWizard.PAGE_WIDTH - CARD_GAP) / 2;
        for (StartupWizard.ChoiceCard card : new StartupWizard.ChoiceCard[]{createCard, importCard}) {
            card.setPrefWidth(cardWidth);
            card.setMaxWidth(cardWidth);
            HBox.setHgrow(card, Priority.ALWAYS);
        }

        HBox cardBox = new HBox(CARD_GAP, createCard, importCard);
        cardBox.setAlignment(Pos.CENTER);

        seedTextArea = new TextArea();
        seedTextArea.getStyleClass().add("wizard-text-area");
        seedTextArea.setPromptText(Res.get("startupWizard.wallet.seed.prompt"));
        seedTextArea.setWrapText(true);
        seedTextArea.setPrefRowCount(3);
        seedTextArea.setMaxWidth(FIELD_WIDTH);

        restoreHeightField = new TextField();
        restoreHeightField.getStyleClass().add("login-password-field");
        restoreHeightField.setPromptText(Res.get("startupWizard.wallet.restoreHeight.prompt"));
        restoreHeightField.setMaxWidth(FIELD_WIDTH);
        restoreHeightField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("[\\d-]*")) restoreHeightField.setText(newValue.replaceAll("[^\\d-]", ""));
        });

        Label restoreHeightInfo = new AutoTooltipLabel(Res.get("startupWizard.wallet.restoreHeight.info"));
        restoreHeightInfo.getStyleClass().add("startup-wizard-footer-label");
        restoreHeightInfo.setWrapText(true);
        restoreHeightInfo.setMaxWidth(FIELD_WIDTH);

        VBox importBox = new VBox(10, seedTextArea, restoreHeightField, restoreHeightInfo);
        importBox.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(importBox, new Insets(5, 0, 0, 0));

        StartupWizard.ChoiceCard.group(() -> {
            statusLabel.setText("");
            boolean importSelected = importCard.isSelected();
            importBox.setVisible(importSelected);
            importBox.setManaged(importSelected);
            if (importSelected) seedTextArea.requestFocus();
        }, createCard, importCard);
        createCard.setSelected(true);
        importBox.setVisible(false);
        importBox.setManaged(false);

        statusLabel.setMinHeight(24);
        statusLabel.setAlignment(Pos.CENTER);

        content = new VBox(12,
                StartupWizard.createHeaderSection(MaterialDesignIcon.WALLET,
                        Res.get("startupWizard.wallet.headline"),
                        Res.get("startupWizard.wallet.subtitle")),
                cardBox,
                importBox,
                statusLabel);
        content.setAlignment(Pos.TOP_CENTER);
        VBox.setMargin(cardBox, new Insets(8, 0, 0, 0));
    }

    @Override
    public Region getContent() {
        return content;
    }

    @Override
    public boolean isSkipped() {
        return quickStart.getAsBoolean();
    }

    @Override
    public void validate(Consumer<Boolean> resultHandler) {
        statusLabel.getStyleClass().remove("error-text");
        statusLabel.setText("");
        if (!importCard.isSelected()) {
            resultHandler.accept(true);
            return;
        }

        String seed = getSeed();
        if (seed == null || seed.split(" ").length != SEED_WORD_COUNT) {
            showError(Res.get("startupWizard.wallet.error.seed"));
            resultHandler.accept(false);
            return;
        }

        if (!restoreHeightField.getText().trim().isEmpty() && getRestoreHeight() == null && getRestoreDate() == null) {
            showError(Res.get("startupWizard.wallet.error.restoreHeight"));
            resultHandler.accept(false);
            return;
        }

        // validate the seed off the JavaFX thread with an offline temporary wallet
        statusLabel.setText(Res.get("startupWizard.wallet.validating"));
        new Thread(() -> {
            boolean valid = isSeedValid(seed);
            UserThread.execute(() -> {
                statusLabel.setText("");
                if (!valid) showError(Res.get("startupWizard.wallet.error.seedInvalid"));
                resultHandler.accept(valid);
            });
        }, "ValidateWalletSeed").start();
    }

    // defer to full validation at wallet creation if validation is unavailable
    private boolean isSeedValid(String seed) {
        try {
            return xmrWalletService.get().isSeedValid(seed);
        } catch (Exception e) {
            log.warn("Could not validate seed, deferring to wallet creation, error={}", e.getMessage());
            return true;
        }
    }

    @Override
    public String getNextButtonText() {
        return Res.get("startupWizard.next");
    }

    /** The normalized seed phrase to import, or null to create a new wallet. */
    @Nullable
    public String getSeed() {
        if (isSkipped() || !importCard.isSelected()) return null;
        String seed = seedTextArea.getText() == null ? "" : seedTextArea.getText().trim().toLowerCase().replaceAll("\\s+", " ");
        return seed.isEmpty() ? null : seed;
    }

    /** The restore height to import at, or null if unset or a date was entered. */
    @Nullable
    public Long getRestoreHeight() {
        String text = restoreHeightField.getText().trim();
        if (getSeed() == null || !text.matches("\\d+")) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The wallet creation date to restore from, or null if unset or a height was entered. */
    @Nullable
    public LocalDate getRestoreDate() {
        String text = restoreHeightField.getText().trim();
        if (getSeed() == null || text.matches("\\d*")) return null;
        try {
            LocalDate date = LocalDate.parse(text);
            return date.isAfter(LocalDate.now()) ? null : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void showError(String message) {
        if (!statusLabel.getStyleClass().contains("error-text")) statusLabel.getStyleClass().add("error-text");
        statusLabel.setText(message);
    }
}
