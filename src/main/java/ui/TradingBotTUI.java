package ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import ibkr.IBKRConnection;
import ibkr.model.AccountSummaryOutput;
import ibkr.model.OrderOutput;
import ibkr.model.PositionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import strategy.Strategy;
import strategy.StrategyRunner;
import trade.TradeJournal;
import trade.TradeRecord;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TradingBotTUI {
    private static final Logger log = LoggerFactory.getLogger(TradingBotTUI.class);

    private final IBKRConnection ibkrConnection;
    private final StrategyRunner strategyRunner;
    private final TradeJournal tradeJournal;

    private SwingTerminalFrame terminal;
    private Screen screen;
    private MultiWindowTextGUI gui;
    private BasicWindow mainWindow;

    // UI Components that need updating
    private Label statusLabel;
    private Label tradingModeLabel;
    private Label marketDataTypeLabel;
    private Label accountLabel;
    private Label pnlLabel;
    private Label positionsCountLabel;
    private Label ordersCountLabel;
    private Panel strategyPanel;
    private Button startStopButton;

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isRunning = true;

    public TradingBotTUI(IBKRConnection ibkrConnection, StrategyRunner strategyRunner, TradeJournal tradeJournal) {
        this.ibkrConnection = ibkrConnection;
        this.strategyRunner = strategyRunner;
        this.tradeJournal = tradeJournal;
    }

    public void start() throws IOException {
        terminal = new DefaultTerminalFactory()
                .setInitialTerminalSize(new TerminalSize(100, 30))
                .createSwingTerminal();
        terminal.setVisible(true);

        // Wait for window to be fully realized before starting screen
        while (!terminal.isDisplayable() || terminal.getWidth() == 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for terminal", e);
            }
        }

        screen = new TerminalScreen(terminal);
        screen.startScreen();

        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE_BRIGHT));

        mainWindow = new BasicWindow("Trading Bot Control Panel");
        mainWindow.setHints(List.of(Window.Hint.CENTERED));

        Panel mainPanel = new Panel();
        mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));

        // Status Section
        Panel statusPanel = createStatusPanel();
        mainPanel.addComponent(statusPanel.withBorder(Borders.singleLine("Status")));

        // Strategies Section
        strategyPanel = createStrategyPanel();
        mainPanel.addComponent(strategyPanel.withBorder(Borders.singleLine("Strategies")));

        // Controls Section
        Panel controlsPanel = createControlsPanel();
        mainPanel.addComponent(controlsPanel.withBorder(Borders.singleLine("Controls")));

        mainWindow.setComponent(mainPanel);

        // Start background refresh
        startRefreshTask();

        // Show GUI (blocks until window is closed)
        gui.addWindowAndWait(mainWindow);

        // Cleanup
        shutdown();
    }

    private Panel createStatusPanel() {
        Panel panel = new Panel(new GridLayout(2));

        panel.addComponent(new Label("Trading Mode:").addStyle(SGR.BOLD));
        tradingModeLabel = new Label("CHECKING...");
        tradingModeLabel.addStyle(SGR.BOLD);
        panel.addComponent(tradingModeLabel);

        panel.addComponent(new Label("Market Data:").addStyle(SGR.BOLD));
        marketDataTypeLabel = new Label("CHECKING...");
        marketDataTypeLabel.addStyle(SGR.BOLD);
        panel.addComponent(marketDataTypeLabel);

        panel.addComponent(new Label("Connection:"));
        statusLabel = new Label("CONNECTED");
        statusLabel.setForegroundColor(TextColor.ANSI.GREEN);
        panel.addComponent(statusLabel);

        panel.addComponent(new Label("Account:"));
        accountLabel = new Label("Loading...");
        panel.addComponent(accountLabel);

        panel.addComponent(new Label("Net Liq:"));
        pnlLabel = new Label("Loading...");
        panel.addComponent(pnlLabel);

        panel.addComponent(new Label("Positions:"));
        positionsCountLabel = new Label("0");
        panel.addComponent(positionsCountLabel);

        panel.addComponent(new Label("Open Orders:"));
        ordersCountLabel = new Label("0");
        panel.addComponent(ordersCountLabel);

        return panel;
    }

    private Panel createStrategyPanel() {
        Panel panel = new Panel(new GridLayout(3));

        // Header
        panel.addComponent(new Label("Strategy").addStyle(SGR.BOLD));
        panel.addComponent(new Label("Hours").addStyle(SGR.BOLD));
        panel.addComponent(new Label("Status").addStyle(SGR.BOLD));

        // Add strategies
        for (Strategy strategy : strategyRunner.getStrategies()) {
            panel.addComponent(new Label(strategy.getName()));
            panel.addComponent(new Label(strategy.getStartTime() + "-" + strategy.getEndTime()));

            Label statusLbl = new Label(strategyRunner.isRunning() ? "RUNNING" : "STOPPED");
            statusLbl.setForegroundColor(strategyRunner.isRunning() ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
            panel.addComponent(statusLbl);
        }

        if (strategyRunner.getStrategies().isEmpty()) {
            panel.addComponent(new Label("No strategies registered"));
            panel.addComponent(new EmptySpace());
            panel.addComponent(new EmptySpace());
        }

        return panel;
    }

    private Panel createControlsPanel() {
        Panel panel = new Panel(new LinearLayout(Direction.HORIZONTAL));

        startStopButton = new Button(strategyRunner.isRunning() ? "Stop" : "Start", this::toggleStrategyRunner);
        panel.addComponent(startStopButton);

        panel.addComponent(new Button("Positions", this::showPositions));
        panel.addComponent(new Button("Orders", this::showOrders));
        panel.addComponent(new Button("P&L", this::showPnL));
        panel.addComponent(new Button("Refresh", this::refreshUI));
        panel.addComponent(new Button("Quit", this::confirmQuit));

        return panel;
    }

    private void toggleStrategyRunner() {
        if (strategyRunner.isRunning()) {
            strategyRunner.stop();
            startStopButton.setLabel("Start");
            log.info("Strategy runner stopped via TUI");
        } else {
            strategyRunner.start();
            startStopButton.setLabel("Stop");
            log.info("Strategy runner started via TUI");
        }
        refreshStrategyPanel();
    }

    private void showPositions() {
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions();

            BasicWindow posWindow = new BasicWindow("Open Positions");
            posWindow.setHints(List.of(Window.Hint.CENTERED));

            Panel panel = new Panel(new GridLayout(4));

            // Header
            panel.addComponent(new Label("Symbol").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Position").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Avg Cost").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Account").addStyle(SGR.BOLD));

            if (positions.isEmpty()) {
                panel.addComponent(new Label("No open positions"));
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
            } else {
                for (PositionOutput pos : positions) {
                    if (!pos.getPos().isZero()) {
                        panel.addComponent(new Label(pos.getContract().symbol()));
                        panel.addComponent(new Label(pos.getPos().toString()));
                        panel.addComponent(new Label(String.format("$%.2f", pos.getAvgCost())));
                        panel.addComponent(new Label(pos.getAccount()));
                    }
                }
            }

            Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
            mainPanel.addComponent(panel);
            mainPanel.addComponent(new EmptySpace());
            mainPanel.addComponent(new Button("Close", posWindow::close));

            posWindow.setComponent(mainPanel);
            gui.addWindowAndWait(posWindow);

        } catch (Exception e) {
            log.error("Failed to fetch positions", e);
            MessageDialog.showMessageDialog(gui, "Error", "Failed to fetch positions: " + e.getMessage());
        }
    }

    private void showOrders() {
        try {
            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();

            BasicWindow ordersWindow = new BasicWindow("Open Orders");
            ordersWindow.setHints(List.of(Window.Hint.CENTERED));

            Panel panel = new Panel(new GridLayout(6));

            // Header
            panel.addComponent(new Label("Symbol").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Action").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Qty").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Type").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Status").addStyle(SGR.BOLD));
            panel.addComponent(new Label("Strategy").addStyle(SGR.BOLD));

            if (orders.isEmpty()) {
                panel.addComponent(new Label("No open orders"));
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
                panel.addComponent(new EmptySpace());
            } else {
                for (OrderOutput order : orders) {
                    panel.addComponent(new Label(order.getContract().symbol()));

                    Label actionLabel = new Label(order.getOrder().action().name());
                    actionLabel.setForegroundColor(
                        order.getOrder().action().name().equals("BUY") ? TextColor.ANSI.GREEN : TextColor.ANSI.RED
                    );
                    panel.addComponent(actionLabel);

                    panel.addComponent(new Label(order.getOrder().totalQuantity().toString()));
                    panel.addComponent(new Label(order.getOrder().orderType().name()));
                    panel.addComponent(new Label(order.getOrderState().status().name()));

                    String strategy = order.getOrder().orderRef();
                    panel.addComponent(new Label(strategy != null && !strategy.isBlank() ? strategy : "-"));
                }
            }

            Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
            mainPanel.addComponent(panel);
            mainPanel.addComponent(new EmptySpace());
            mainPanel.addComponent(new Button("Close", ordersWindow::close));

            ordersWindow.setComponent(mainPanel);
            gui.addWindowAndWait(ordersWindow);

        } catch (Exception e) {
            log.error("Failed to fetch orders", e);
            MessageDialog.showMessageDialog(gui, "Error", "Failed to fetch orders: " + e.getMessage());
        }
    }

    private static final String ALL_STRATEGIES = "All Strategies";

    private static final String[] PERIOD_LABELS  = {"Today (1d)", "Last 7 days", "Last 30 days", "Last 90 days"};
    private static final int[]    PERIOD_DAYS     = {1,            7,              30,              90};

    private void showPnL() {
        showPnLDialog(ALL_STRATEGIES, 1);
    }

    private void showPnLDialog(String strategyFilter, int loadedDays) {
        List<TradeRecord> allTrades = tradeJournal.getCompletedTrades();

        // Build strategy list dynamically from journal
        List<String> strategyOptions = new ArrayList<>();
        strategyOptions.add(ALL_STRATEGIES);
        allTrades.stream()
                .map(TradeRecord::getStrategy)
                .distinct()
                .sorted()
                .forEach(strategyOptions::add);

        // Apply strategy filter
        List<TradeRecord> filteredTrades = allTrades.stream()
                .filter(t -> strategyFilter.equals(ALL_STRATEGIES) || t.getStrategy().equals(strategyFilter))
                .collect(Collectors.toList());

        BasicWindow pnlWindow = new BasicWindow("P&L Report");
        pnlWindow.setHints(List.of(Window.Hint.CENTERED));

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));

        // --- History load panel ---
        Panel historyRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        historyRow.addComponent(new Label("Period:"));
        ComboBox<String> periodCombo = new ComboBox<>(PERIOD_LABELS);
        // Pre-select the combo to match currently loaded period
        for (int i = 0; i < PERIOD_DAYS.length; i++) {
            if (PERIOD_DAYS[i] == loadedDays) { periodCombo.setSelectedIndex(i); break; }
        }
        historyRow.addComponent(periodCombo);
        historyRow.addComponent(new Button("Load", () -> {
            int idx = periodCombo.getSelectedIndex();
            int days = PERIOD_DAYS[idx >= 0 && idx < PERIOD_DAYS.length ? idx : 0];
            pnlWindow.close();
            ibkrConnection.reqExecutions(days);
            MessageDialog.showMessageDialog(gui, "Loading",
                    "Fetching " + PERIOD_LABELS[idx] + " of history from IBKR.\n" +
                    "Reopen P&L in a few seconds to see the results.");
        }));
        mainPanel.addComponent(historyRow.withBorder(Borders.singleLine("History")));

        // --- Strategy filter panel ---
        Panel strategyRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        strategyRow.addComponent(new Label("Strategy:"));
        ComboBox<String> strategyCombo = new ComboBox<>(strategyOptions.toArray(new String[0]));
        int stratIdx = strategyOptions.indexOf(strategyFilter);
        if (stratIdx >= 0) strategyCombo.setSelectedIndex(stratIdx);
        strategyRow.addComponent(strategyCombo);
        strategyRow.addComponent(new Button("Apply", () -> {
            String selected = strategyCombo.getSelectedItem();
            pnlWindow.close();
            showPnLDialog(selected != null ? selected : ALL_STRATEGIES, loadedDays);
        }));
        mainPanel.addComponent(strategyRow.withBorder(Borders.singleLine("Filter")));

        mainPanel.addComponent(new EmptySpace());

        // --- Strategy Summary ---
        mainPanel.addComponent(new Label("Strategy Summary").addStyle(SGR.BOLD));

        Map<String, TradeJournal.StrategySummary> summaries = buildSummaries(filteredTrades);

        if (summaries.isEmpty()) {
            mainPanel.addComponent(new Label("No completed trades for selected filter."));
        } else {
            Panel summaryPanel = new Panel(new GridLayout(5));
            summaryPanel.addComponent(new Label("Strategy").addStyle(SGR.BOLD));
            summaryPanel.addComponent(new Label("Trades").addStyle(SGR.BOLD));
            summaryPanel.addComponent(new Label("Winners").addStyle(SGR.BOLD));
            summaryPanel.addComponent(new Label("Net P&L").addStyle(SGR.BOLD));
            summaryPanel.addComponent(new Label("Commission").addStyle(SGR.BOLD));

            for (TradeJournal.StrategySummary s : summaries.values()) {
                summaryPanel.addComponent(new Label(s.strategy()));
                summaryPanel.addComponent(new Label(String.valueOf(s.totalTrades())));

                int winPct = s.totalTrades() > 0 ? (int) (100.0 * s.winningTrades() / s.totalTrades()) : 0;
                summaryPanel.addComponent(new Label(s.winningTrades() + " (" + winPct + "%)"));

                Label netPnLLabel = new Label(String.format("%+$.2f", s.totalNetPnL()));
                netPnLLabel.setForegroundColor(s.totalNetPnL() >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                summaryPanel.addComponent(netPnLLabel);

                summaryPanel.addComponent(new Label(String.format("-$%.2f", Math.abs(s.totalCommission()))));
            }
            mainPanel.addComponent(summaryPanel.withBorder(Borders.singleLine()));
        }

        mainPanel.addComponent(new EmptySpace());

        // --- Trade History ---
        mainPanel.addComponent(new Label("Trade History").addStyle(SGR.BOLD));

        if (filteredTrades.isEmpty()) {
            mainPanel.addComponent(new Label("No trades for selected filter."));
        } else {
            Panel histPanel = new Panel(new GridLayout(6));
            histPanel.addComponent(new Label("Time").addStyle(SGR.BOLD));
            histPanel.addComponent(new Label("Symbol").addStyle(SGR.BOLD));
            histPanel.addComponent(new Label("Qty").addStyle(SGR.BOLD));
            histPanel.addComponent(new Label("Fill $").addStyle(SGR.BOLD));
            histPanel.addComponent(new Label("P&L").addStyle(SGR.BOLD));
            histPanel.addComponent(new Label("Strategy").addStyle(SGR.BOLD));

            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss");
            for (TradeRecord trade : filteredTrades) {
                String timeStr = trade.getTime() != null ? trade.getTime().format(timeFmt) : "-";
                histPanel.addComponent(new Label(timeStr));
                histPanel.addComponent(new Label(trade.getSymbol()));
                histPanel.addComponent(new Label(String.valueOf((int) trade.getShares())));
                histPanel.addComponent(new Label(String.format("$%.2f", trade.getFillPrice())));

                if (trade.isClosingTrade()) {
                    Label pnlLabel = new Label(String.format("%+$.2f", trade.getNetPnL()));
                    pnlLabel.setForegroundColor(trade.getNetPnL() >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                    histPanel.addComponent(pnlLabel);
                } else {
                    histPanel.addComponent(new Label("OPEN"));
                }

                histPanel.addComponent(new Label(trade.getStrategy()));
            }
            mainPanel.addComponent(histPanel.withBorder(Borders.singleLine()));
        }

        mainPanel.addComponent(new EmptySpace());
        mainPanel.addComponent(new Button("Close", pnlWindow::close));

        pnlWindow.setComponent(mainPanel);
        gui.addWindowAndWait(pnlWindow);
    }

    /** Builds strategy summaries from an arbitrary list of trades (supports filtered views). */
    private Map<String, TradeJournal.StrategySummary> buildSummaries(List<TradeRecord> trades) {
        Map<String, List<TradeRecord>> byStrategy = new LinkedHashMap<>();
        for (TradeRecord t : trades) {
            byStrategy.computeIfAbsent(t.getStrategy(), k -> new ArrayList<>()).add(t);
        }
        Map<String, TradeJournal.StrategySummary> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<TradeRecord>> e : byStrategy.entrySet()) {
            List<TradeRecord> ts = e.getValue();
            int total = ts.size();
            int winners = (int) ts.stream().filter(t -> t.isClosingTrade() && t.getNetPnL() > 0).count();
            double netPnL = ts.stream().filter(TradeRecord::isClosingTrade).mapToDouble(TradeRecord::getNetPnL).sum();
            double commission = ts.stream().mapToDouble(TradeRecord::getCommission).sum();
            result.put(e.getKey(), new TradeJournal.StrategySummary(e.getKey(), total, winners, netPnL, commission));
        }
        return result;
    }

    private void refreshUI() {
        refreshStatusPanel();
        refreshStrategyPanel();
    }

    private void refreshStatusPanel() {
        try {
            // Update connection status based on actual state
            IBKRConnection.ConnectionState state = ibkrConnection.getConnectionState();
            switch (state) {
                case CONNECTED:
                    statusLabel.setText("CONNECTED");
                    statusLabel.setForegroundColor(TextColor.ANSI.GREEN);
                    break;
                case CONNECTING:
                    statusLabel.setText("CONNECTING...");
                    statusLabel.setForegroundColor(TextColor.ANSI.YELLOW);
                    break;
                case RECONNECTING:
                    statusLabel.setText("RECONNECTING...");
                    statusLabel.setForegroundColor(TextColor.ANSI.YELLOW);
                    break;
                case DISCONNECTED:
                    statusLabel.setText("DISCONNECTED");
                    statusLabel.setForegroundColor(TextColor.ANSI.RED);
                    break;
                case FAILED:
                    statusLabel.setText("FAILED");
                    statusLabel.setForegroundColor(TextColor.ANSI.RED);
                    break;
            }

            // Get account info
            List<AccountSummaryOutput> accountSummary = ibkrConnection.reqAccountSummary("NetLiquidation,AccountType");

            for (AccountSummaryOutput summary : accountSummary) {
                if ("NetLiquidation".equals(summary.getTag())) {
                    pnlLabel.setText("$" + summary.getValue());
                }
                if ("AccountType".equals(summary.getTag())) {
                    accountLabel.setText(summary.getAccount() + " (" + summary.getValue() + ")");

                    // Determine trading mode from account ID
                    // Paper accounts start with "DU" or "DF", live accounts start with "U"
                    String accountId = summary.getAccount();
                    boolean isPaper = accountId.startsWith("DU") || accountId.startsWith("DF");
                    tradingModeLabel.setText(isPaper ? "PAPER" : "LIVE");
                    tradingModeLabel.setForegroundColor(isPaper ? TextColor.ANSI.YELLOW : TextColor.ANSI.RED_BRIGHT);
                    tradingModeLabel.setBackgroundColor(isPaper ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE);
                }
            }

            // Get positions count
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            long activePositions = positions.stream().filter(p -> !p.getPos().isZero()).count();
            positionsCountLabel.setText(String.valueOf(activePositions));

            // Get open orders count
            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();
            ordersCountLabel.setText(String.valueOf(orders.size()));

            // Update market data type
            String marketDataType = ibkrConnection.getMarketDataTypeString();
            boolean isDelayed = ibkrConnection.isMarketDataDelayed();
            marketDataTypeLabel.setText(marketDataType);
            marketDataTypeLabel.setForegroundColor(isDelayed ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.GREEN);
            if (isDelayed) {
                marketDataTypeLabel.setBackgroundColor(TextColor.ANSI.YELLOW);
            }

        } catch (Exception e) {
            // If this fails, likely disconnected
            statusLabel.setText("ERROR");
            statusLabel.setForegroundColor(TextColor.ANSI.RED);
            log.warn("Failed to refresh status: {}", e.getMessage());
        }
    }

    private void refreshStrategyPanel() {
        // Update strategy status labels
        startStopButton.setLabel(strategyRunner.isRunning() ? "Stop" : "Start");
    }

    private void confirmQuit() {
        MessageDialogButton result = MessageDialog.showMessageDialog(
                gui,
                "Confirm Exit",
                "Are you sure you want to quit?\nThis will stop all strategies and disconnect.",
                MessageDialogButton.Yes,
                MessageDialogButton.No
        );

        if (result == MessageDialogButton.Yes) {
            isRunning = false;
            mainWindow.close();
        }
    }

    private void startRefreshTask() {
        refresher.scheduleAtFixedRate(() -> {
            if (isRunning && gui != null) {
                try {
                    gui.getGUIThread().invokeLater(this::refreshUI);
                } catch (Exception e) {
                    log.trace("Refresh failed: {}", e.getMessage());
                }
            }
        }, 5, 30, TimeUnit.SECONDS);
    }

    private void shutdown() {
        isRunning = false;
        refresher.shutdown();
        try {
            screen.stopScreen();
        } catch (IOException e) {
            log.error("Error stopping screen", e);
        }
    }
}
