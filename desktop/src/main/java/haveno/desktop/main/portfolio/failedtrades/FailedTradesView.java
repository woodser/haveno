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

package haveno.desktop.main.portfolio.failedtrades;

import com.google.inject.Inject;
import com.googlecode.jcsv.writer.CSVEntryConverter;
import com.jfoenix.controls.JFXButton;
import de.jensd.fx.fontawesome.AwesomeIcon;
import haveno.common.util.Utilities;
import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.trade.Contract;
import haveno.core.trade.HavenoUtils;
import haveno.core.trade.Trade;
import haveno.core.user.User;
import haveno.core.xmr.wallet.XmrWalletService;
import haveno.desktop.common.view.ActivatableViewAndModel;
import haveno.desktop.common.view.FxmlView;
import haveno.desktop.components.AutoTooltipButton;
import haveno.desktop.components.AutoTooltipLabel;
import haveno.desktop.components.HyperlinkWithIcon;
import haveno.desktop.components.InputTextField;
import haveno.desktop.main.offer.OfferViewUtil;
import haveno.desktop.main.overlays.popups.Popup;
import haveno.desktop.main.overlays.windows.TradeDetailsWindow;
import haveno.desktop.util.FormBuilder;
import haveno.desktop.util.GUIUtil;
import java.math.BigInteger;
import java.util.Comparator;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;

@FxmlView
public class FailedTradesView extends ActivatableViewAndModel<VBox, FailedTradesViewModel> {

    @FXML
    TableView<FailedTradesListItem> tableView;
    @FXML
    TableColumn<FailedTradesListItem, FailedTradesListItem> priceColumn, amountColumn, volumeColumn,
            marketColumn, directionColumn, dateColumn, tradeIdColumn, stateColumn, removeTradeColumn;
    @FXML
    HBox searchBox;
    @FXML
    AutoTooltipLabel filterLabel;
    @FXML
    InputTextField filterTextField;
    @FXML
    Pane searchBoxSpacer;
    @FXML
    Label numItems;
    @FXML
    Region footerSpacer;
    @FXML
    AutoTooltipButton exportButton;

    private final TradeDetailsWindow tradeDetailsWindow;
    private SortedList<FailedTradesListItem> sortedList;
    private FilteredList<FailedTradesListItem> filteredList;
    private EventHandler<KeyEvent> keyEventEventHandler;
    private ChangeListener<String> filterTextFieldListener;
    private Scene scene;
    private XmrWalletService xmrWalletService;
    private User user;
    private ContextMenu contextMenu;

    @Inject
    public FailedTradesView(FailedTradesViewModel model,
                            TradeDetailsWindow tradeDetailsWindow,
                            XmrWalletService xmrWalletService,
                            User user) {
        super(model);
        this.tradeDetailsWindow = tradeDetailsWindow;
        this.xmrWalletService = xmrWalletService;
        this.user = user;
    }

    @Override
    public void initialize() {
        GUIUtil.applyTableStyle(tableView);

        priceColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.price")));
        amountColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amountWithCur", Res.getBaseCurrencyCode())));
        volumeColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.amount")));
        marketColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.market")));
        directionColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.offerType")));
        dateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.dateTime")));
        tradeIdColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.tradeId")));
        stateColumn.setGraphic(new AutoTooltipLabel(Res.get("shared.state")));

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new AutoTooltipLabel(Res.get("table.placeholder.noItems", Res.get("shared.trades"))));

        setTradeIdColumnCellFactory();
        setDirectionColumnCellFactory();
        setAmountColumnCellFactory();
        setPriceColumnCellFactory();
        setVolumeColumnCellFactory();
        setDateColumnCellFactory();
        setMarketColumnCellFactory();
        setStateColumnCellFactory();
        setRemoveTradeColumnCellFactory();

        tradeIdColumn.setComparator(Comparator.comparing(o -> o.getTrade().getId()));
        dateColumn.setComparator(Comparator.comparing(o -> o.getTrade().getDate()));
        priceColumn.setComparator(Comparator.comparing(o -> o.getTrade().getPrice()));
        volumeColumn.setComparator(Comparator.comparing(o -> o.getTrade().getVolume(), Comparator.nullsFirst(Comparator.naturalOrder())));
        amountColumn.setComparator(Comparator.comparing(o -> o.getTrade().getAmount(), Comparator.nullsFirst(Comparator.naturalOrder())));
        stateColumn.setComparator(Comparator.comparing(model::getState));
        marketColumn.setComparator(Comparator.comparing(model::getMarketLabel));

        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);

        keyEventEventHandler = keyEvent -> {
            if (Utilities.isAltOrCtrlPressed(KeyCode.Y, keyEvent)) {
                var checkTxs = checkTxs();
                var checkUnfailString = checkUnfail();
                if (!checkTxs.isEmpty()) {
                    log.warn("Cannot unfail, error {}", checkTxs);
                    new Popup().warning(checkTxs)
                            .show();
                } else if (!checkUnfailString.isEmpty()) {
                    log.warn("Cannot unfail, error {}", checkUnfailString);
                    new Popup().warning(Res.get("portfolio.failed.cantUnfail", checkUnfailString))
                            .show();
                } else {
                    new Popup().warning(Res.get("portfolio.failed.unfail"))
                            .onAction(this::onUnfail)
                            .show();
                }
            }
        };

        filterLabel.setText(Res.get("shared.filter"));
        HBox.setMargin(filterLabel, new Insets(5, 0, 0, 10));
        filterTextFieldListener = (observable, oldValue, newValue) -> applyFilteredListPredicate(filterTextField.getText());
        searchBox.setSpacing(5);
        HBox.setHgrow(searchBoxSpacer, Priority.ALWAYS);

        numItems.setId("num-offers");
        numItems.setPadding(new Insets(-5, 0, 0, 10));
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox.setMargin(exportButton, new Insets(0, 10, 0, 0));
        exportButton.updateText(Res.get("shared.exportCSV"));
    }

    @Override
    protected void activate() {
        scene = root.getScene();
        if (scene != null) {
            scene.addEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }

        filteredList = new FilteredList<>(model.getList());
        sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);

        contextMenu = new ContextMenu();
        boolean isArbitrator = user.getRegisteredArbitrator() != null;
        if (isArbitrator) {
            MenuItem item1 = new MenuItem(Res.get("support.contextmenu.penalize.msg", Res.get("shared.maker")));
            MenuItem item2 = new MenuItem(Res.get("support.contextmenu.penalize.msg", Res.get("shared.taker")));

            item1.setOnAction(event -> {
                Trade selectedFailedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
                handleContextMenu("portfolio.failed.penalty.msg",
                        Res.get(selectedFailedTrade.getMaker() == selectedFailedTrade.getBuyer() ? "shared.buyer" : "shared.seller"),
                        Res.get("shared.maker"),
                        selectedFailedTrade.getMaker().getSecurityDeposit(),
                        selectedFailedTrade.getMaker().getReserveTxHash(),
                        selectedFailedTrade.getMaker().getReserveTxHex());
            });
    
            item2.setOnAction(event -> {
                Trade selectedFailedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
                handleContextMenu("portfolio.failed.penalty.msg",
                        Res.get(selectedFailedTrade.getTaker() == selectedFailedTrade.getBuyer() ? "shared.buyer" : "shared.seller"),
                        Res.get("shared.taker"),
                        selectedFailedTrade.getTaker().getSecurityDeposit(),
                        selectedFailedTrade.getTaker().getReserveTxHash(),
                        selectedFailedTrade.getTaker().getReserveTxHex());
            });

            contextMenu.getItems().addAll(item1, item2);
        }

        tableView.setRowFactory(tv -> {
            TableRow<FailedTradesListItem> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                contextMenu.show(row, event.getScreenX(), event.getScreenY());
            });
            return row;
        });

        numItems.setText(Res.get("shared.numItemsLabel", sortedList.size()));
        exportButton.setOnAction(event -> {
            ObservableList<TableColumn<FailedTradesListItem, ?>> tableColumns = tableView.getColumns();
            int reportColumns = tableColumns.size() - 1;    // CSV report excludes the last column (an icon)
            CSVEntryConverter<FailedTradesListItem> headerConverter = item -> {
                String[] columns = new String[reportColumns];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = ((AutoTooltipLabel) tableColumns.get(i).getGraphic()).getText();
                return columns;
            };
            CSVEntryConverter<FailedTradesListItem> contentConverter = item -> {
                String[] columns = new String[reportColumns];
                columns[0] = model.getTradeId(item);
                columns[1] = model.getDate(item);
                columns[2] = model.getMarketLabel(item);
                columns[3] = model.getPrice(item);
                columns[4] = model.getAmount(item);
                columns[5] = model.getVolume(item);
                columns[6] = model.getDirectionLabel(item);
                columns[7] = model.getState(item);
                return columns;
            };

            GUIUtil.exportCSV("failedTrades.csv",
                    headerConverter,
                    contentConverter,
                    new FailedTradesListItem(),
                    sortedList,
                    (Stage) root.getScene().getWindow());
        });

        filterTextField.textProperty().addListener(filterTextFieldListener);
        applyFilteredListPredicate(filterTextField.getText());
    }

    private void handleContextMenu(String msgKey, String buyerOrSeller, String makerOrTaker, BigInteger fee, String reserveTxHash, String reserveTxHex) {
        final Trade failedTrade = tableView.getSelectionModel().getSelectedItem().getTrade();
        log.debug("Found {} matching trade.", (failedTrade != null ? failedTrade.getId() : null));
        if(failedTrade != null) {
            new Popup().warning(Res.get(msgKey,
                    buyerOrSeller,
                    makerOrTaker,
                    HavenoUtils.formatXmr(fee, true),
                    "todo", // TODO: set reserve tx miner fee when verified
                    reserveTxHash
                    )
            ).onAction(() -> OfferViewUtil.submitTransactionHex(xmrWalletService, tableView, reserveTxHex)).show();
        } else {
            new Popup().error(Res.get("portfolio.failed.error.msg")).show();
        }
    }

    @Override
    protected void deactivate() {
        if (scene != null) {
            scene.removeEventHandler(KeyEvent.KEY_RELEASED, keyEventEventHandler);
        }

        sortedList.comparatorProperty().unbind();
        exportButton.setOnAction(null);

        filterTextField.textProperty().removeListener(filterTextFieldListener);
    }

    private void applyFilteredListPredicate(String filterString) {
        filteredList.setPredicate(item -> {
            if (filterString.isEmpty())
                return true;

            Offer offer = item.getTrade().getOffer();

            if (offer.getId().contains(filterString)) {
                return true;
            }
            if (model.getDate(item).contains(filterString)) {
                return true;
            }
            if (model.getMarketLabel(item).contains(filterString)) {
                return true;
            }
            if (model.getPrice(item).contains(filterString)) {
                return true;
            }
            if (model.getVolume(item).contains(filterString)) {
                return true;
            }
            if (model.getAmount(item).contains(filterString)) {
                return true;
            }
            if (model.getDirectionLabel(item).contains(filterString)) {
                return true;
            }

            Trade trade = item.getTrade();

            if (trade.getMaker().getDepositTxHash() != null && trade.getMaker().getDepositTxHash().contains(filterString)) {
                return true;
            }
            if (trade.getTaker().getDepositTxHash() != null && trade.getTaker().getDepositTxHash().contains(filterString)) {
                return true;
            }
            if (trade.getPayoutTxId() != null && trade.getPayoutTxId().contains(filterString)) {
                return true;
            }

            Contract contract = trade.getContract();

            boolean isBuyerOnion = false;
            boolean isSellerOnion = false;
            boolean matchesBuyersPaymentAccountData = false;
            boolean matchesSellersPaymentAccountData = false;
            if (contract != null) {
                isBuyerOnion = contract.getBuyerNodeAddress().getFullAddress().contains(filterString);
                isSellerOnion = contract.getSellerNodeAddress().getFullAddress().contains(filterString);
                matchesBuyersPaymentAccountData = trade.getBuyer().getPaymentAccountPayload().getPaymentDetails().contains(filterString);
                matchesSellersPaymentAccountData = trade.getSeller().getPaymentAccountPayload().getPaymentDetails().contains(filterString);
            }
            return isBuyerOnion || isSellerOnion ||
                    matchesBuyersPaymentAccountData || matchesSellersPaymentAccountData;
        });
    }

    private void onUnfail() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        model.dataModel.unfailTrade(trade);
    }

    private String checkUnfail() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        return model.dataModel.checkUnfail(trade);
    }

    private String checkTxs() {
        Trade trade = sortedList.get(tableView.getSelectionModel().getFocusedIndex()).getTrade();
        log.info("Initiated unfail of trade {}", trade.getId());
        if (trade.getMakerDepositTx() == null || (trade.getTakerDepositTx() == null && !trade.hasBuyerAsTakerWithoutDeposit())) {
            log.info("Check unfail found no deposit tx(s) for trade {}", trade.getId());
            return Res.get("portfolio.failed.depositTxNull");
        }
        return "";
    }

    private void onRevertTrade(Trade trade) {
        new Popup().attention(Res.get("portfolio.failed.revertToPending.popup"))
                .onAction(() -> onMoveTradeToPendingTrades(trade))
                .actionButtonText(Res.get("shared.yes"))
                .closeButtonText(Res.get("shared.no"))
                .show();
    }

    private void onMoveTradeToPendingTrades(Trade trade) {
        model.dataModel.onMoveTradeToPendingTrades(trade);
    }

    private void setTradeIdColumnCellFactory() {
        tradeIdColumn.setCellValueFactory((offerListItem) -> new ReadOnlyObjectWrapper<>(offerListItem.getValue()));
        tradeIdColumn.setCellFactory(
                new Callback<>() {

                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(TableColumn<FailedTradesListItem,
                            FailedTradesListItem> column) {
                        return new TableCell<>() {
                            private HyperlinkWithIcon field;

                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null && !empty) {
                                    field = new HyperlinkWithIcon(model.getTradeId(item));
                                    field.setOnAction(event -> tradeDetailsWindow.show(item.getTrade()));
                                    field.setTooltip(new Tooltip(Res.get("tooltip.openPopupForDetails")));
                                    setGraphic(field);
                                } else {
                                    setGraphic(null);
                                    if (field != null)
                                        field.setOnAction(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setDateColumnCellFactory() {
        dateColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        dateColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setGraphic(new AutoTooltipLabel(model.getDate(item)));
                                else
                                    setGraphic(null);
                            }
                        };
                    }
                });
    }

    private void setMarketColumnCellFactory() {
        marketColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        marketColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic(new AutoTooltipLabel(model.getMarketLabel(item)));
                            }
                        };
                    }
                });
    }

    private void setStateColumnCellFactory() {
        stateColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        stateColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setGraphic(new AutoTooltipLabel(model.getState(item)));
                                else
                                    setGraphic(null);
                            }
                        };
                    }
                });
    }


    private void setAmountColumnCellFactory() {
        amountColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        amountColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic(new AutoTooltipLabel(model.getAmount(item)));
                            }
                        };
                    }
                });
    }

    private void setPriceColumnCellFactory() {
        priceColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        priceColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic(new AutoTooltipLabel(model.getPrice(item)));
                            }
                        };
                    }
                });
    }

    private void setVolumeColumnCellFactory() {
        volumeColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        volumeColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setGraphic(new AutoTooltipLabel(model.getVolume(item)));
                                else
                                    setGraphic(null);
                            }
                        };
                    }
                });
    }

    private void setDirectionColumnCellFactory() {
        directionColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        directionColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(
                            TableColumn<FailedTradesListItem, FailedTradesListItem> column) {
                        return new TableCell<>() {
                            @Override
                            public void updateItem(final FailedTradesListItem item, boolean empty) {
                                super.updateItem(item, empty);
                                setGraphic(new AutoTooltipLabel(model.getDirectionLabel(item)));
                            }
                        };
                    }
                });
    }

    private TableColumn<FailedTradesListItem, FailedTradesListItem> setRemoveTradeColumnCellFactory() {
        removeTradeColumn.setCellValueFactory((trade) -> new ReadOnlyObjectWrapper<>(trade.getValue()));
        removeTradeColumn.setCellFactory(
                new Callback<>() {
                    @Override
                    public TableCell<FailedTradesListItem, FailedTradesListItem> call(TableColumn<FailedTradesListItem,
                            FailedTradesListItem> column) {
                        return new TableCell<>() {

                            @Override
                            public void updateItem(FailedTradesListItem newItem, boolean empty) {
                                super.updateItem(newItem, empty);
                                if (!empty && newItem != null && newItem.getTrade().isDepositsPublished()) {
                                    Label icon = FormBuilder.getIcon(AwesomeIcon.UNDO);
                                    JFXButton iconButton = new JFXButton("", icon);
                                    iconButton.setStyle("-fx-cursor: hand;");
                                    iconButton.getStyleClass().add("hidden-icon-button");
                                    iconButton.setTooltip(new Tooltip(Res.get("portfolio.failed.revertToPending")));
                                    iconButton.setOnAction(e -> onRevertTrade(newItem.getTrade()));
                                    setGraphic(iconButton);
                                } else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
        return removeTradeColumn;
    }
}

