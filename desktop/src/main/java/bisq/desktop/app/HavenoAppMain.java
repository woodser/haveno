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

package bisq.desktop.app;

import bisq.desktop.common.UITimer;
import bisq.desktop.common.view.guice.InjectorViewFactory;
import bisq.desktop.setup.DesktopPersistedDataHost;
import bisq.desktop.util.ImageUtil;

import bisq.core.app.AvoidStandbyModeService;
import bisq.core.app.HavenoExecutable;

import bisq.common.UserThread;
import bisq.common.app.AppModule;
import bisq.common.app.Version;

import javafx.application.Application;
import javafx.application.Platform;

import javafx.stage.Stage;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HavenoAppMain extends HavenoExecutable {

    public static final String DEFAULT_APP_NAME = "Haveno";

    private HavenoApp application;

    public HavenoAppMain() {
        super("Bisq Desktop", "bisq-desktop", DEFAULT_APP_NAME, Version.VERSION);
    }

    public static void main(String[] args) {
        // For some reason the JavaFX launch process results in us losing the thread
        // context class loader: reset it. In order to work around a bug in JavaFX 8u25
        // and below, you must include the following code as the first line of your
        // realMain method:
        Thread.currentThread().setContextClassLoader(HavenoAppMain.class.getClassLoader());

        new HavenoAppMain().execute(args);
    }

    @Override
    public void onSetupComplete() {
        log.debug("onSetupComplete");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // First synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void configUserThread() {
        UserThread.setExecutor(Platform::runLater);
        UserThread.setTimerClass(UITimer.class);
    }

    @Override
    protected void launchApplication() {
        HavenoApp.setAppLaunchedHandler(application -> {
            HavenoAppMain.this.application = (HavenoApp) application;
            // Map to user thread!
            UserThread.execute(this::onApplicationLaunched);
        });

        Application.launch(HavenoApp.class);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // As application is a JavaFX application we need to wait for onApplicationLaunched
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onApplicationLaunched() {
        super.onApplicationLaunched();
        application.setGracefulShutDownHandler(this);
    }

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        application.handleUncaughtException(throwable, doShutDown);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected AppModule getModule() {
        return new HavenoAppModule(config);
    }

    @Override
    protected void applyInjector() {
        super.applyInjector();

        application.setInjector(injector);
        injector.getInstance(InjectorViewFactory.class).setInjector(injector);
    }

    @Override
    protected void readAllPersisted(Runnable completeHandler) {
        super.readAllPersisted(DesktopPersistedDataHost.getPersistedDataHosts(injector), completeHandler);
    }

    @Override
    protected void setupAvoidStandbyMode() {
        injector.getInstance(AvoidStandbyModeService.class).init();
    }

    @Override
    protected void startApplication() {
        log.info("Running startApplication...");
        // We need to be in user thread! We mapped at launchApplication already.  Once
        // the UI is ready we get onApplicationStarted called and start the setup there.
        application.startApplication(this::onApplicationStarted);
    }

    @Override
    protected void onApplicationStarted() {
        super.onApplicationStarted();

        // Relevant to have this in the logs, for support cases
        // This can only be called after JavaFX is initialized, otherwise the version logged will be null
        // Therefore, calling this as part of onApplicationStarted()
        log.info("Using JavaFX {}", System.getProperty("javafx.version"));
    }

    @Override
    public CompletableFuture<String> handlePasswordDialogLogin() {
        CompletableFuture<String> passwordFuture = new CompletableFuture<>();

        Platform.setImplicitExit(false);
        Platform.runLater(() -> {
            // create the password dialog
            PasswordDialog passwordDialog = new PasswordDialog();
            passwordDialog.setTitle("Enter Password");
            passwordDialog.setHeaderText("Please enter your password:");

            // wait for the user to enter a password
            Optional<String> result = passwordDialog.showAndWait();
            if (result.isPresent()) {
                // if the user entered a password, complete the passwordFuture with the password
                passwordFuture.complete(result.get());
            } else {
                // if the user cancelled the dialog, complete the passwordFuture exceptionally
                passwordFuture.completeExceptionally(new Exception("Password dialog cancelled"));
            }
        });

        return passwordFuture;
    }

    public class PasswordDialog extends Dialog<String> {

        public PasswordDialog() {
            setTitle("Password Dialog");
            setHeaderText("Please enter your password:");

            // Create the password field
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("Password");

            // Add an icon to the dialog
            Stage stage = (Stage) getDialogPane().getScene().getWindow();
            stage.getIcons().add(ImageUtil.getImageByName("lock.png"));

            // Set the dialog content
            VBox vbox = new VBox(10);
            vbox.getChildren().addAll(new ImageView(ImageUtil.getImageByName("password.png")), passwordField);
            getDialogPane().setContent(vbox);

            // Add OK and Cancel buttons
            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

            // Convert the result to a string when the OK button is clicked
            setResultConverter(buttonType -> {
                if (buttonType == okButton) {
                    return passwordField.getText();
                } else {
                    new Thread(() -> HavenoApp.getShutDownHandler().run()).start();
                    return null;
                }
            });
        }
    }
}
