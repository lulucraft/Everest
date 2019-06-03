/*
 * Copyright 2019 Rohit Awate.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rohitawate.everest.controllers.auth.oauth2;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXRippler;
import com.jfoenix.controls.JFXTextField;
import com.rohitawate.everest.Main;
import com.rohitawate.everest.auth.AuthProvider;
import com.rohitawate.everest.auth.captors.CaptureMethod;
import com.rohitawate.everest.auth.oauth2.AuthorizationCodeProvider;
import com.rohitawate.everest.auth.oauth2.exceptions.AccessTokenDeniedException;
import com.rohitawate.everest.auth.oauth2.exceptions.AuthWindowClosedException;
import com.rohitawate.everest.auth.oauth2.exceptions.NoAuthorizationGrantException;
import com.rohitawate.everest.auth.oauth2.tokens.AuthCodeToken;
import com.rohitawate.everest.controllers.DashboardController;
import com.rohitawate.everest.logging.Logger;
import com.rohitawate.everest.misc.EverestUtilities;
import com.rohitawate.everest.notifications.NotificationsManager;
import com.rohitawate.everest.state.auth.AuthorizationCodeState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Cursor;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;

public class AuthorizationCodeController implements Initializable {
    @FXML
    private VBox authCodeBox, accessTokenBox;
    @FXML
    private JFXCheckBox enabled;
    @FXML
    private ComboBox<String> captureMethodBox;
    @FXML
    private JFXTextField authURLField, tokenURLField, redirectURLField,
            clientIDField, clientSecretField, scopeField, stateField,
            headerPrefixField, accessTokenField, refreshTokenField;
    @FXML
    private Label expiryLabel;
    @FXML
    private JFXButton refreshTokenButton;

    private JFXRippler rippler;

    private AuthorizationCodeState state;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        captureMethodBox.getItems().addAll(CaptureMethod.BROWSER, CaptureMethod.WEB_VIEW);
        captureMethodBox.setValue(CaptureMethod.BROWSER);
        refreshTokenButton.setOnAction(this::refreshToken);
        expiryLabel.setVisible(false);

        rippler = new JFXRippler(accessTokenBox);
        rippler.setPrefSize(authCodeBox.getPrefWidth(), authCodeBox.getPrefHeight());
        authCodeBox.getChildren().add(rippler);

        Platform.runLater(() -> {
            if (Main.preferences.auth.enableAccessTokenExpiryTimer) {
                Timeline timeline = new Timeline();
                timeline.setCycleCount(Timeline.INDEFINITE);
                timeline.getKeyFrames().add(
                        new KeyFrame(Duration.seconds(1),
                                new EventHandler<ActionEvent>() {
                                    @Override
                                    public void handle(ActionEvent event) {
                                        setExpiryLabel();
                                    }
                                })
                );

                timeline.play();
            } else {
                expiryLabel.setOnMouseClicked(e -> setExpiryLabel());
                expiryLabel.setTooltip(new Tooltip("Click to update expiry status"));
                expiryLabel.setCursor(Cursor.HAND);
            }
        });
    }

    private void refreshToken(ActionEvent actionEvent) {
        /*
            Opening a system browser window need not be done on the JavaFX Application Thread.
            Hence, this is performed on a separate thread.

            However, a WebView can only be opened on the JavaFX Application Thread hence it is
            NOT performed on some other thread.
         */
        TokenFetcher tokenFetcher = new TokenFetcher();
        if (captureMethodBox.getValue().equals(CaptureMethod.BROWSER)) {
            ExecutorService service = EverestUtilities.newDaemonSingleThreadExecutor();
            service.submit(tokenFetcher);
        } else {
            try {
                state.accessToken = tokenFetcher.call();
                onRefreshSucceeded();
            } catch (Exception e) {
                onRefreshFailed(e);
            }
        }
    }

    public AuthorizationCodeState getState() {
        if (state == null) {
            state = new AuthorizationCodeState();
            return state;
        }

        state.captureMethod = captureMethodBox.getValue();
        state.authURL = authURLField.getText();
        state.accessTokenURL = tokenURLField.getText();
        state.redirectURL = redirectURLField.getText();
        state.clientID = clientIDField.getText();
        state.clientSecret = clientSecretField.getText();
        state.scope = scopeField.getText();
        state.state = stateField.getText();
        state.headerPrefix = headerPrefixField.getText();
        state.enabled = enabled.isSelected();

        // Setting these values again since they can be modified from the UI
        state.accessToken.setAccessToken(accessTokenField.getText());
        state.accessToken.setRefreshToken(refreshTokenField.getText());

        return state;
    }

    public void setState(AuthorizationCodeState authCodeState) {
        this.state = authCodeState;

        if (authCodeState != null) {
            captureMethodBox.setValue(state.captureMethod);

            authURLField.setText(state.authURL);
            tokenURLField.setText(state.accessTokenURL);
            redirectURLField.setText(state.redirectURL);

            clientIDField.setText(state.clientID);
            clientSecretField.setText(state.clientSecret);

            scopeField.setText(state.scope);
            stateField.setText(state.state);
            headerPrefixField.setText(state.headerPrefix);
            enabled.setSelected(state.enabled);

            if (state.accessToken != null) {
                onRefreshSucceeded();
            }
        }
    }

    private void setExpiryLabel() {
        if (state != null && state.accessToken.getTimeToExpiry() >= 0) {
            expiryLabel.setVisible(true);

            if (state.accessToken.getExpiresIn() == 0) {
                expiryLabel.setText("Never expires.");
            } else {
                long timeToExpiry = state.accessToken.getTimeToExpiry();
                if (timeToExpiry < 0) {
                    expiryLabel.setText("Token expired.");
                } else {
                    int hours, minutes, seconds;
                    hours = (int) (timeToExpiry / 3600);
                    timeToExpiry %= 3600;
                    minutes = (int) timeToExpiry / 60;
                    seconds = (int) timeToExpiry % 60;

                    Platform.runLater(() -> {
                        if (hours == 0 && minutes != 0) {
                            expiryLabel.setText(String.format("Expires in %dm %ds", minutes, seconds));
                        } else if (hours == 0 && minutes == 0) {
                            expiryLabel.setText(String.format("Expires in %ds", seconds));
                        } else {
                            expiryLabel.setText(String.format("Expires in %dh %dm %ds", hours, minutes, seconds));
                        }
                    });
                }
            }
        }
    }

    public void reset() {
        authURLField.clear();
        tokenURLField.clear();
        redirectURLField.clear();
        clientIDField.clear();
        clientSecretField.clear();
        scopeField.clear();
        stateField.clear();
        headerPrefixField.clear();
        accessTokenField.clear();
        refreshTokenField.clear();
        expiryLabel.setVisible(false);
        enabled.setSelected(false);
        state = null;
    }

    public AuthProvider getAuthProvider() {
        /*
            This method is always called on the JavaFX application thread, which is also required for
            creating and using the WebView. Hence, refreshToken() is called here itself if the accessToken is absent,
            so that when RequestManager invokes AuthCodeProvider's getAuthHeader() from a different thread,
            the accessToken is already present and hence the WebView wouldn't need to be opened.
         */
        String token = accessTokenField.getText();
        if (token != null && token.isEmpty() &&
                enabled.isSelected() && captureMethodBox.getValue().equals(CaptureMethod.WEB_VIEW)) {
            refreshToken(null);
        }

        return new AuthorizationCodeProvider(this);
    }

    private void onRefreshSucceeded() {
        accessTokenField.clear();
        refreshTokenField.clear();

        accessTokenField.setText(state.accessToken.getAccessToken());

        if (state.accessToken.getRefreshToken() != null) {
            refreshTokenField.setText(state.accessToken.getRefreshToken());
        }

        setExpiryLabel();

        rippler.createManualRipple().run();
    }

    private void onRefreshFailed(Throwable exception) {
        String errorMessage;
        if (exception.getClass().equals(AuthWindowClosedException.class)) {
            // DashboardController already shows an error for this
            return;
        } else if (exception.getClass().equals(NoAuthorizationGrantException.class)) {
            errorMessage = "Grant denied by authorization endpoint.";
        } else if (exception.getClass().equals(AccessTokenDeniedException.class)) {
            errorMessage = "Access accessToken denied by accessToken endpoint.";
        } else if (exception.getClass().equals(MalformedURLException.class)) {
            errorMessage = "Invalid URL(s).";
        } else {
            errorMessage = "Could not refresh OAuth 2.0 Authorization Code tokens.";
        }

        NotificationsManager.push(DashboardController.CHANNEL_ID, errorMessage, 10000);
        Logger.warning(errorMessage, (Exception) exception);
    }

    public void setAccessToken(AuthCodeToken accessToken) {
        state.accessToken = accessToken;
        Platform.runLater(() -> {
            onRefreshSucceeded();
            accessTokenField.requestLayout();
            refreshTokenField.requestLayout();
        });
    }

    private class TokenFetcher extends Task<AuthCodeToken> {
        @Override
        protected AuthCodeToken call() throws Exception {
            AuthorizationCodeProvider provider = new AuthorizationCodeProvider(AuthorizationCodeController.this);
            return provider.getAccessToken();
        }

        @Override
        protected void succeeded() {
            state.accessToken = getValue();
            onRefreshSucceeded();
        }

        @Override
        protected void failed() {
            Throwable exception = getException();
            onRefreshFailed(exception);
        }
    }
}
