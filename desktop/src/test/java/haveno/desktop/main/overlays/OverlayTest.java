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

package haveno.desktop.main.overlays;

import haveno.common.UserThread;
import haveno.core.api.CoreNotificationService;
import haveno.core.support.SupportType;
import haveno.core.support.dispute.Dispute;
import haveno.core.support.dispute.arbitration.ArbitrationManager;
import haveno.core.support.dispute.mediation.MediationManager;
import haveno.core.support.dispute.refund.RefundManager;
import haveno.core.support.messages.ChatMessage;
import haveno.core.trade.Trade;
import haveno.core.trade.TradeManager;
import haveno.core.user.Preferences;
import haveno.desktop.Navigation;
import haveno.desktop.main.overlays.notifications.NotificationCenter;
import haveno.network.p2p.NodeAddress;
import haveno.proto.grpc.NotificationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OverlayTest {

    @Test
    public void typeSafeCreation() {
        new A();
        new C();
        new D<>();
    }

    @Test
    public void typeUnsafeCreation() {
        assertThrows(RuntimeException.class, () -> new B());
    }

    @Nested
    class UnreadTradeChat {
        private final ObservableList<Trade> trades = FXCollections.observableArrayList();
        private final ObservableList<Dispute> disputes = FXCollections.observableArrayList();
        private final TradeManager tradeManager = mock(TradeManager.class);
        private final CoreNotificationService notificationService = new CoreNotificationService();
        private NotificationCenter notificationCenter;
        private Executor originalExecutor;
        private int nextTradeId;

        @BeforeEach
        void setUp() {
            originalExecutor = UserThread.getExecutor();
            UserThread.setExecutor(Runnable::run);
            ArbitrationManager arbitrationManager = mock(ArbitrationManager.class);
            MediationManager mediationManager = mock(MediationManager.class);
            RefundManager refundManager = mock(RefundManager.class);
            Preferences preferences = mock(Preferences.class);
            when(preferences.getUseAnimationsProperty()).thenReturn(new SimpleBooleanProperty());
            when(tradeManager.getObservableList()).thenReturn(trades);
            when(tradeManager.getNotificationService()).thenReturn(notificationService);
            when(arbitrationManager.getDisputesAsObservableList()).thenReturn(disputes);
            when(mediationManager.getDisputesAsObservableList()).thenReturn(FXCollections.observableArrayList());
            when(refundManager.getDisputesAsObservableList()).thenReturn(FXCollections.observableArrayList());
            notificationCenter = new NotificationCenter(tradeManager, arbitrationManager, mediationManager,
                    refundManager, preferences, mock(Navigation.class));
        }

        @AfterEach
        void tearDown() {
            UserThread.setExecutor(originalExecutor);
        }

        @Test
        void restoresUnreadMessagesForMakerAndTakerUntilBothChatsAreRead() {
            Trade maker = addTrade(true);
            Trade taker = addTrade(false);
            maker.getChatMessages().add(message(maker, true));
            taker.getChatMessages().add(message(taker, true));
            notificationCenter.onAllServicesAndViewsInitialized();
            assertTrue(hasUnreadChat());

            markRead(maker);
            assertTrue(hasUnreadChat());
            markRead(taker);
            assertFalse(hasUnreadChat());
        }

        @Test
        void ignoresOutgoingDisplayedSystemAndArbitratorMessages() {
            for (boolean maker : List.of(true, false)) {
                Trade trade = addTrade(maker);
                ChatMessage displayed = message(trade, true);
                displayed.setWasDisplayed(true);
                ChatMessage system = message(trade, true);
                system.setSystemMessage(true);
                trade.getChatMessages().addAll(message(trade, false), displayed, system);
            }
            Trade arbitrator = addTrade(true);
            when(arbitrator.isArbitrator()).thenReturn(true);
            arbitrator.getChatMessages().add(message(arbitrator, true));
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(hasUnreadChat());
        }

        @Test
        void observesNewTradesAndClearsWhenUnreadTradeIsRemoved() {
            notificationCenter.onAllServicesAndViewsInitialized();
            assertFalse(hasUnreadChat());
            Trade trade = addTrade(true);
            trade.getChatMessages().add(message(trade, true));
            assertTrue(hasUnreadChat());
            trades.remove(trade);
            assertFalse(hasUnreadChat());
            trades.add(trade);
            assertTrue(hasUnreadChat());
            trade.getChatMessages().clear();
            assertFalse(hasUnreadChat());
        }

        @Test
        void keepsSupportAndTradeStatusNotificationsSeparate() {
            Trade trade = addTrade(true);
            Dispute dispute = mock(Dispute.class);
            ChatMessage supportMessage = new ChatMessage(SupportType.ARBITRATION, trade.getId(), 0,
                    false, "Support message", new NodeAddress("peer:9999"));
            when(dispute.getChatMessages()).thenReturn(FXCollections.observableArrayList(supportMessage));
            disputes.add(dispute);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationService.sendNotification(NotificationMessage.newBuilder()
                    .setType(NotificationMessage.NotificationType.TRADE_UPDATE).build());
            assertFalse(hasUnreadChat());
            verify(dispute).refreshAlertLevel(false);
        }

        @Test
        void doesNotMarkMessagesReadWhenTradeIsOnlySelected() {
            Trade trade = addTrade(true);
            ChatMessage message = message(trade, true);
            trade.getChatMessages().add(message);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.setSelectedTradeId(trade.getId());
            assertTrue(hasUnreadChat());
            assertFalse(message.isWasDisplayed());
        }

        @Test
        void suppressesMessagesInOpenChatAndPersistsTheirReadState() {
            Trade trade = addTrade(false);
            notificationCenter.onAllServicesAndViewsInitialized();
            notificationCenter.onChatOpened(trade.getChatMessages());
            ChatMessage message = message(trade, true);
            trade.getChatMessages().add(message);
            assertFalse(hasUnreadChat());
            notificationService.sendNotification(NotificationMessage.newBuilder()
                    .setType(NotificationMessage.NotificationType.CHAT_MESSAGE)
                    .setChatMessage(protobuf.ChatMessage.newBuilder().setType(protobuf.SupportType.TRADE)
                            .setTradeId(trade.getId()).setUid(message.getUid())).build());
            assertTrue(message.isWasDisplayed());
            verify(tradeManager).requestPersistence();
            notificationCenter.onChatClosed(trade.getChatMessages());
            assertFalse(hasUnreadChat());
            trade.getChatMessages().add(message(trade, true));
            assertTrue(hasUnreadChat());
        }

        @Test
        void dispatchesUnreadUpdatesThroughUserThread() {
            Trade trade = addTrade(true);
            notificationCenter.onAllServicesAndViewsInitialized();
            List<Runnable> updates = new ArrayList<>();
            UserThread.setExecutor(updates::add);
            trade.getChatMessages().add(message(trade, true));
            assertFalse(hasUnreadChat());
            assertFalse(updates.isEmpty());
            updates.forEach(Runnable::run);
            assertTrue(hasUnreadChat());
        }

        private Trade addTrade(boolean maker) {
            Trade trade = mock(Trade.class);
            when(trade.getId()).thenReturn("trade-" + nextTradeId++);
            when(trade.isMaker()).thenReturn(maker);
            when(trade.getChatMessages()).thenReturn(FXCollections.observableArrayList());
            when(trade.statePhaseProperty()).thenReturn(new SimpleObjectProperty<>(Trade.Phase.INIT));
            when(trade.disputeStateProperty()).thenReturn(new SimpleObjectProperty<>(Trade.DisputeState.NO_DISPUTE));
            when(tradeManager.getOpenTrade(trade.getId())).thenReturn(Optional.of(trade));
            trades.add(trade);
            return trade;
        }

        private ChatMessage message(Trade trade, boolean incoming) {
            return new ChatMessage(SupportType.TRADE, trade.getId(), 0,
                    incoming ? trade.isMaker() : !trade.isMaker(), "Peer message", new NodeAddress("peer:9999"));
        }

        private void markRead(Trade trade) {
            trade.getChatMessages().forEach(message -> message.setWasDisplayed(true));
            notificationCenter.onChatOpened(trade.getChatMessages());
            notificationCenter.onChatClosed(trade.getChatMessages());
        }

        private boolean hasUnreadChat() {
            return notificationCenter.unreadTradeChatProperty().get();
        }
    }

    private static class A extends Overlay<A> {
    }

    private static class B extends Overlay<A> {
    }

    private static class C extends TabbedOverlay<C> {
    }

    private static class D<T> extends Overlay<D<T>> {
    }
}
