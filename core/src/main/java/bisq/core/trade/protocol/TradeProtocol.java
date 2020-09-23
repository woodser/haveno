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

package bisq.core.trade.protocol;

import bisq.core.trade.MakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.messages.CounterCurrencyTransferStartedMessage;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.MediatedPayoutTxPublishedMessage;
import bisq.core.trade.messages.MediatedPayoutTxSignatureMessage;
import bisq.core.trade.messages.PeerPublishedDelayedPayoutTxMessage;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.protocol.tasks.ApplyFilter;
import bisq.core.trade.protocol.tasks.ProcessPeerPublishedDelayedPayoutTxMessage;
import bisq.core.trade.protocol.tasks.mediation.BroadcastMediatedPayoutTx;
import bisq.core.trade.protocol.tasks.mediation.FinalizeMediatedPayoutTx;
import bisq.core.trade.protocol.tasks.mediation.ProcessMediatedPayoutSignatureMessage;
import bisq.core.trade.protocol.tasks.mediation.ProcessMediatedPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.mediation.SendMediatedPayoutSignatureMessage;
import bisq.core.trade.protocol.tasks.mediation.SendMediatedPayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.mediation.SetupMediatedPayoutTxListener;
import bisq.core.trade.protocol.tasks.mediation.SignMediatedPayoutTx;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.MailboxMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendMailboxMessageListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.PubKeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.taskrunner.Task;

import javafx.beans.value.ChangeListener;

import java.security.PublicKey;

import java.util.HashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static bisq.core.util.Validator.isTradeIdValid;
import static bisq.core.util.Validator.nonEmptyStringOf;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public abstract class TradeProtocol {
    interface Event {
        String name();
    }

    enum DisputeEvent implements TradeProtocol.Event {
        MEDIATION_RESULT_ACCEPTED,
        MEDIATION_RESULT_REJECTED
    }

    private static final long DEFAULT_TIMEOUT_SEC = 180;

    protected final ProcessModel processModel;
    private final DecryptedDirectMessageListener decryptedDirectMessageListener;
    private final ChangeListener<Trade.State> stateChangeListener;
    protected Trade trade;
    private Timer timeoutTimer;

    public TradeProtocol(Trade trade) {
        this.trade = trade;
        this.processModel = trade.getProcessModel();

        decryptedDirectMessageListener = (decryptedMessageWithPubKey, peersNodeAddress) -> {
            // We check the sig only as soon we have stored the peers pubKeyRing.
            PubKeyRing tradingPeerPubKeyRing = processModel.getTradingPeer().getPubKeyRing();
            PublicKey signaturePubKey = decryptedMessageWithPubKey.getSignaturePubKey();
            if (tradingPeerPubKeyRing != null && signaturePubKey.equals(tradingPeerPubKeyRing.getSignaturePubKey())) {
                NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
                if (networkEnvelope instanceof TradeMessage) {
                    TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
                    nonEmptyStringOf(tradeMessage.getTradeId());

                    if (tradeMessage.getTradeId().equals(processModel.getOfferId())) {
                        doHandleDecryptedMessage(tradeMessage, peersNodeAddress);
                    }
                } else if (networkEnvelope instanceof AckMessage) {
                    AckMessage ackMessage = (AckMessage) networkEnvelope;
                    if (ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE &&
                            ackMessage.getSourceId().equals(trade.getId())) {
                        // We handle the ack for CounterCurrencyTransferStartedMessage and DepositTxAndDelayedPayoutTxMessage
                        // as we support automatic re-send of the msg in case it was not ACKed after a certain time
                        if (ackMessage.getSourceMsgClassName().equals(CounterCurrencyTransferStartedMessage.class.getSimpleName())) {
                            processModel.setPaymentStartedAckMessage(ackMessage);
                        } else if (ackMessage.getSourceMsgClassName().equals(DepositTxAndDelayedPayoutTxMessage.class.getSimpleName())) {
                            processModel.setDepositTxSentAckMessage(ackMessage);
                        }

                        if (ackMessage.isSuccess()) {
                            log.info("Received AckMessage for {} from {} with tradeId {} and uid {}",
                                    ackMessage.getSourceMsgClassName(), peersNodeAddress, ackMessage.getSourceId(), ackMessage.getSourceUid());
                        } else {
                            log.warn("Received AckMessage with error state for {} from {} with tradeId {} and errorMessage={}",
                                    ackMessage.getSourceMsgClassName(), peersNodeAddress, ackMessage.getSourceId(), ackMessage.getErrorMessage());
                        }
                    }
                }
            }
        };
        processModel.getP2PService().addDecryptedDirectMessageListener(decryptedDirectMessageListener);

        stateChangeListener = (observable, oldValue, newValue) -> {
            if (newValue.getPhase() == Trade.Phase.TAKER_FEE_PUBLISHED && trade instanceof MakerTrade)
                processModel.getOpenOfferManager().closeOpenOffer(checkNotNull(trade.getOffer()));
        };
        trade.stateProperty().addListener(stateChangeListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mediation: Called from UI if trader accepts mediation result
    ///////////////////////////////////////////////////////////////////////////////////////////

    //TODO

    // Trader has not yet received the peer's signature but has clicked the accept button.
    public void onAcceptMediationResult(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (trade.getProcessModel().getTradingPeer().getMediatedPayoutTxSignature() != null) {
            errorMessageHandler.handleErrorMessage("We have received already the signature from the peer.");
            return;
        }
        DisputeEvent event = DisputeEvent.MEDIATION_RESULT_ACCEPTED;
        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                () -> {
                    resultHandler.handleResult();
                    handleTaskRunnerSuccess(event);
                },
                (errorMessage) -> {
                    errorMessageHandler.handleErrorMessage(errorMessage);
                    handleTaskRunnerFault(event, errorMessage);
                });
        taskRunner.addTasks(
                ApplyFilter.class,
                SignMediatedPayoutTx.class,
                SendMediatedPayoutSignatureMessage.class,
                SetupMediatedPayoutTxListener.class
        );
        taskRunner.run();
    }


    // Trader has already received the peer's signature and has clicked the accept button as well.
    public void onFinalizeMediationResultPayout(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        if (trade.getPayoutTx() != null) {
            errorMessageHandler.handleErrorMessage("Payout tx is already published.");
            return;
        }

        DisputeEvent event = DisputeEvent.MEDIATION_RESULT_ACCEPTED;
        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                () -> {
                    resultHandler.handleResult();
                    handleTaskRunnerSuccess(event);
                },
                (errorMessage) -> {
                    errorMessageHandler.handleErrorMessage(errorMessage);
                    handleTaskRunnerFault(event, errorMessage);
                });
        taskRunner.addTasks(
                ApplyFilter.class,
                SignMediatedPayoutTx.class,
                FinalizeMediatedPayoutTx.class,
                BroadcastMediatedPayoutTx.class,
                SendMediatedPayoutTxPublishedMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mediation: incoming message
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handle(MediatedPayoutTxSignatureMessage tradeMessage, NodeAddress sender) {
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(sender);

        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                () -> handleTaskRunnerSuccess(tradeMessage),
                errorMessage -> handleTaskRunnerFault(tradeMessage, errorMessage));

        taskRunner.addTasks(
                ProcessMediatedPayoutSignatureMessage.class
        );
        taskRunner.run();
    }

    protected void handle(MediatedPayoutTxPublishedMessage tradeMessage, NodeAddress sender) {
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(sender);

        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                () -> handleTaskRunnerSuccess(tradeMessage),
                errorMessage -> handleTaskRunnerFault(tradeMessage, errorMessage));

        taskRunner.addTasks(
                ProcessMediatedPayoutTxPublishedMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Peer has published the delayed payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(PeerPublishedDelayedPayoutTxMessage tradeMessage, NodeAddress sender) {
        processModel.setTradeMessage(tradeMessage);
        processModel.setTempTradingPeerNodeAddress(sender);

        TradeTaskRunner taskRunner = new TradeTaskRunner(trade,
                () -> handleTaskRunnerSuccess(tradeMessage),
                errorMessage -> handleTaskRunnerFault(tradeMessage, errorMessage));

        taskRunner.addTasks(
                ProcessPeerPublishedDelayedPayoutTxMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void doHandleDecryptedMessage(TradeMessage tradeMessage, NodeAddress sender) {
        if (tradeMessage instanceof MediatedPayoutTxSignatureMessage) {
            handle((MediatedPayoutTxSignatureMessage) tradeMessage, sender);
        } else if (tradeMessage instanceof MediatedPayoutTxPublishedMessage) {
            handle((MediatedPayoutTxPublishedMessage) tradeMessage, sender);
        } else if (tradeMessage instanceof PeerPublishedDelayedPayoutTxMessage) {
            handle((PeerPublishedDelayedPayoutTxMessage) tradeMessage, sender);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void completed() {
        cleanup();
    }

    private void cleanup() {
        stopTimeout();
        trade.stateProperty().removeListener(stateChangeListener);
        // We removed that from here earlier as it broke the trade process in some non critical error cases.
        // But it should be actually removed...
        processModel.getP2PService().removeDecryptedDirectMessageListener(decryptedDirectMessageListener);
    }

    public void applyMailboxMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, Trade trade) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (processModel.getTradingPeer().getPubKeyRing() != null &&
                decryptedMessageWithPubKey.getSignaturePubKey().equals(processModel.getTradingPeer().getPubKeyRing().getSignaturePubKey())) {
            processModel.setDecryptedMessageWithPubKey(decryptedMessageWithPubKey);

            if (networkEnvelope instanceof MailboxMessage && networkEnvelope instanceof TradeMessage) {
                this.trade = trade;
                TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
                NodeAddress peerNodeAddress = ((MailboxMessage) networkEnvelope).getSenderNodeAddress();
                doApplyMailboxTradeMessage(tradeMessage, peerNodeAddress);
            }
        } else {
            log.error("SignaturePubKey in message does not match the SignaturePubKey we have stored to that trading peer.");
        }
    }

    protected void doApplyMailboxTradeMessage(TradeMessage tradeMessage, NodeAddress peerNodeAddress) {
        log.info("Received {} as MailboxMessage from {} with tradeId {} and uid {}",
                tradeMessage.getClass().getSimpleName(), peerNodeAddress, tradeMessage.getTradeId(), tradeMessage.getUid());

        if (tradeMessage instanceof MediatedPayoutTxSignatureMessage) {
            handle((MediatedPayoutTxSignatureMessage) tradeMessage, peerNodeAddress);
        } else if (tradeMessage instanceof MediatedPayoutTxPublishedMessage) {
            handle((MediatedPayoutTxPublishedMessage) tradeMessage, peerNodeAddress);
        } else if (tradeMessage instanceof PeerPublishedDelayedPayoutTxMessage) {
            handle((PeerPublishedDelayedPayoutTxMessage) tradeMessage, peerNodeAddress);
        }
    }

    protected void startTimeout() {
        startTimeout(DEFAULT_TIMEOUT_SEC);
    }

    protected void startTimeout(long timeoutSec) {
        stopTimeout();

        timeoutTimer = UserThread.runAfter(() -> {
            log.error("Timeout reached. TradeID={}, state={}, timeoutSec={}",
                    trade.getId(), trade.stateProperty().get(), timeoutSec);
            trade.setErrorMessage("Timeout reached. Protocol did not complete in " + timeoutSec + " sec.");
            cleanupTradeOnFault();
            cleanup();
        }, timeoutSec);
    }

    protected void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    protected void handleTaskRunnerSuccess(TradeMessage tradeMessage) {
        handleTaskRunnerSuccess(tradeMessage, null);
    }

    protected void handleTaskRunnerSuccess(Event event) {
        handleTaskRunnerSuccess(null, event.name());
    }

    private void handleTaskRunnerSuccess(@Nullable TradeMessage tradeMessage, @Nullable String trigger) {
        String triggerEvent = trigger != null ? trigger :
                tradeMessage != null ? tradeMessage.getClass().getSimpleName() : "N/A";
        log.info("TaskRunner successfully completed. {}", "Triggered from message " + triggerEvent);

        sendAckMessage(tradeMessage, true, null);
    }

    protected void handleTaskRunnerFault(@Nullable TradeMessage tradeMessage, String errorMessage) {
        log.error("Task runner failed on {} with error {}", tradeMessage, errorMessage);

        sendAckMessage(tradeMessage, false, errorMessage);

        cleanupTradeOnFault();
        cleanup();
    }

    protected void handleTaskRunnerFault(@Nullable Event event, String errorMessage) {
        log.error("Task runner failed on {} with error {}", event, errorMessage);

        cleanupTradeOnFault();
        cleanup();
    }

    protected boolean wasDisputed() {
        return trade.getDisputeState() != Trade.DisputeState.NO_DISPUTE;
    }

    protected void sendAckMessage(@Nullable TradeMessage tradeMessage, boolean result, @Nullable String errorMessage) {
        // We complete at initial protocol setup with the setup listener tasks.
        // Other cases are if we start from an UI event the task runner (payment started, confirmed).
        // In such cases we have not set any tradeMessage and we ignore the sendAckMessage call.
        if (tradeMessage == null)
            return;

        String tradeId = tradeMessage.getTradeId();
        String sourceUid = tradeMessage.getUid();

        AckMessage ackMessage = new AckMessage(processModel.getMyNodeAddress(),
                AckMessageSourceType.TRADE_MESSAGE,
                tradeMessage.getClass().getSimpleName(),
                sourceUid,
                tradeId,
                result,
                errorMessage);
        // If there was an error during offer verification, the tradingPeerNodeAddress of the trade might not be set yet.
        // We can find the peer's node address in the processModel's tempTradingPeerNodeAddress in that case.
        NodeAddress peersNodeAddress = trade.getTradingPeerNodeAddress() != null ?
                trade.getTradingPeerNodeAddress() :
                processModel.getTempTradingPeerNodeAddress();
        log.info("Send AckMessage for {} to peer {}. tradeId={}, sourceUid={}",
                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, sourceUid);
        processModel.getP2PService().sendEncryptedMailboxMessage(
                peersNodeAddress,
                processModel.getTradingPeer().getPubKeyRing(),
                ackMessage,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for {} arrived at peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, sourceUid);
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("AckMessage for {} stored in mailbox for peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, sourceUid);
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for {} failed. Peer {}. tradeId={}, sourceUid={}, errorMessage={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, sourceUid, errorMessage);
                    }
                }
        );
    }

    private void cleanupTradeOnFault() {
        Trade.State state = trade.getState();
        log.warn("cleanupTradableOnFault tradeState={}", state);
        TradeManager tradeManager = processModel.getTradeManager();
        if (trade.isInPreparation()) {
            // no funds left. we just clean up the trade list
            tradeManager.removePreparedTrade(trade);
        } else if (!trade.isFundsLockedIn()) {
            if (processModel.getPreparedDepositTx() == null) {
                if (trade.isTakerFeePublished()) {
                    tradeManager.addTradeToFailedTrades(trade);
                } else {
                    tradeManager.addTradeToClosedTrades(trade);
                }
            } else {
                log.error("We have already sent the prepared deposit tx to the peer but we did not received the reply " +
                        "about the deposit tx nor saw it in the network. tradeId={}, tradeState={}", trade.getId(), trade.getState());
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // FluentProcess
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected FluentProcess expectedPhase(Trade.Phase phase) {
        return new FluentProcess(trade, phase);
    }

    protected FluentProcess expectedPhases(Trade.Phase... phase) {
        return new FluentProcess(trade, phase);
    }

    class FluentProcess {
        private final Trade trade;
        @Nullable
        private TradeMessage message;
        private final Set<Trade.Phase> expectedPhases = new HashSet<>();
        private final Set<Boolean> preConditions = new HashSet<>();
        @Nullable
        private Event event;
        private Runnable preConditionFailedHandler;
        private int timeoutSec;
        private NodeAddress peersNodeAddress;
        private TradeTaskRunner taskRunner;

        public FluentProcess(Trade trade,
                             Trade.Phase expectedPhase) {
            this.trade = trade;
            this.expectedPhases.add(expectedPhase);
        }

        public FluentProcess(Trade trade,
                             Trade.Phase... expectedPhases) {
            this.trade = trade;
            this.expectedPhases.addAll(Set.of(expectedPhases));
        }

        public FluentProcess run() {
            boolean allPreConditionsMet = preConditions.stream().allMatch(e -> e);
            boolean isTradeIdValid = message == null || isTradeIdValid(processModel.getOfferId(), message);

            if (isPhaseValid() && allPreConditionsMet && isTradeIdValid) {
                if (timeoutSec > 0) {
                    startTimeout(timeoutSec);
                }

                if (peersNodeAddress != null) {
                    processModel.setTempTradingPeerNodeAddress(peersNodeAddress);
                }

                if (message != null) {
                    processModel.setTradeMessage(message);
                }

                taskRunner.run();
            }

            if (!allPreConditionsMet && preConditionFailedHandler != null) {
                preConditionFailedHandler.run();
            }

            return this;
        }

       /* protected FluentProcess defaultTaskRunner(Consumer<TradeTaskRunner> consumer) {
            taskRunner = new TradeTaskRunner(trade,
                    () -> handleTaskRunnerSuccess(message),
                    errorMessage -> handleTaskRunnerFault(message, errorMessage));

            consumer.accept(taskRunner);

            return this;
        }*/

        @SafeVarargs
        public final FluentProcess addTasks(Class<? extends Task<Trade>>... tasks) {
            if (taskRunner == null) {
                if (message != null) {
                    taskRunner = new TradeTaskRunner(trade,
                            () -> handleTaskRunnerSuccess(message),
                            errorMessage -> handleTaskRunnerFault(message, errorMessage));
                } else if (event != null) {
                    taskRunner = new TradeTaskRunner(trade,
                            () -> handleTaskRunnerSuccess(event),
                            errorMessage -> handleTaskRunnerFault(event, errorMessage));
                } else {
                    throw new IllegalStateException("addTasks must not be called without message or event " +
                            "set in case no taskRunner has been created yet");
                }
            }
            taskRunner.addTasks(tasks);
            return this;
        }

        private boolean isPhaseValid() {
            boolean isPhaseValid = expectedPhases.stream().anyMatch(e -> e == trade.getPhase());
            String trigger = message != null ?
                    message.getClass().getSimpleName() :
                    event != null ?
                            event.name() + " event" :
                            "";
            if (isPhaseValid) {
                log.info("We received {} at phase {} and state {}",
                        trigger,
                        trade.getPhase(),
                        trade.getState());
            } else {
                log.error("We received {} but we are are not in the correct phase. Expected phases={}, " +
                                "Trade phase={}, Trade state= {} ",
                        trigger,
                        expectedPhases,
                        trade.getPhase(),
                        trade.getState());
            }

            return isPhaseValid;
        }

        public FluentProcess orInPhase(Trade.Phase phase) {
            expectedPhases.add(phase);
            return this;
        }

        public FluentProcess on(Event event) {
            this.event = event;
            return this;
        }

        public FluentProcess on(TradeMessage tradeMessage) {
            this.message = tradeMessage;
            return this;
        }

        public FluentProcess preCondition(boolean preCondition) {
            preConditions.add(preCondition);
            return this;
        }

        public FluentProcess preCondition(boolean preCondition, Runnable conditionFailedHandler) {
            preConditions.add(preCondition);
            this.preConditionFailedHandler = conditionFailedHandler;
            return this;
        }

        public FluentProcess withTimeout(int timeoutSec) {
            this.timeoutSec = timeoutSec;
            return this;
        }

        public FluentProcess from(NodeAddress peersNodeAddress) {
            this.peersNodeAddress = peersNodeAddress;
            return this;
        }

        public FluentProcess setTaskRunner(TradeTaskRunner taskRunner) {
            this.taskRunner = taskRunner;
            return this;
        }
    }
}
