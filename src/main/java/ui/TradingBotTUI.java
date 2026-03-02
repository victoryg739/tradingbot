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
import java.util.concurrent.CompletableFuture;

public class TradingBotTUI {
    private static final Logger log = LoggerFactory.getLogger(TradingBotTUI.class);

    private final IBKRConnection ibkrConnection;
    private final StrategyRunner strategyRunner;
    private final TradeJournal tradeJournal;

    private SwingTerminalFrame terminal;
    private Screen screen;
    private MultiWindowTextGUI gui;
    private BasicWindow mainWindow;

    // Status panel labels
    private Label statusLabel;
    private Label tradingModeLabel;
    private Label marketDataTypeLabel;
    private Label accountLabel;
    private Label netLiqLabel;
    private Label positionsCountLabel;
    private Label ordersCountLabel;
    private Label journalTradesLabel;
    private Label journalPnLLabel;
    private Button startStopButton;
    private Panel strategyPanel;

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isRunning = true;

    // P&L dialog filter state (persists between opens)
    private String lastStrategyFilter = "All Strategies";
    private int lastLoadedDays = 1;

    public TradingBotTUI(IBKRConnection ibkrConnection, StrategyRunner strategyRunner, TradeJournal tradeJournal) {
        this.ibkrConnection = ibkrConnection;
        this.strategyRunner = strategyRunner;
        this.tradeJournal = tradeJournal;
    }

    public void start() throws IOException {
        terminal = new DefaultTerminalFactory()
                .setInitialTerminalSize(new TerminalSize(130, 42))
                .createSwingTerminal();
        terminal.setTitle("Trading Bot");
        terminal.setVisible(true);

        while (!terminal.isDisplayable() || terminal.getWidth() == 0) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for terminal", e);
            }
        }

        screen = new TerminalScreen(terminal);
        screen.startScreen();

        gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE_BRIGHT));

        mainWindow = new BasicWindow("  Trading Bot Control Panel  ");
        mainWindow.setHints(List.of(Window.Hint.CENTERED));

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        mainPanel.addComponent(createStatusPanel().withBorder(Borders.singleLine("Status")));
        mainPanel.addComponent(createStrategyPanel().withBorder(Borders.singleLine("Strategies")));
        mainPanel.addComponent(createControlsPanel().withBorder(Borders.singleLine("Controls")));

        mainWindow.setComponent(mainPanel);
        startRefreshTask();
        gui.addWindowAndWait(mainWindow);
        shutdown();
    }

    // -------------------------------------------------------------------------
    // Main panel sections
    // -------------------------------------------------------------------------

    private Panel createStatusPanel() {
        Panel panel = new Panel(new GridLayout(4));

        // Row 1
        panel.addComponent(new Label("Connection:").addStyle(SGR.BOLD));
        statusLabel = new Label("CONNECTED");
        statusLabel.setForegroundColor(TextColor.ANSI.GREEN);
        panel.addComponent(statusLabel);

        panel.addComponent(new Label("Trading Mode:").addStyle(SGR.BOLD));
        tradingModeLabel = new Label("CHECKING...");
        tradingModeLabel.addStyle(SGR.BOLD);
        panel.addComponent(tradingModeLabel);

        // Row 2
        panel.addComponent(new Label("Account:").addStyle(SGR.BOLD));
        accountLabel = new Label("Loading...");
        panel.addComponent(accountLabel);

        panel.addComponent(new Label("Market Data:").addStyle(SGR.BOLD));
        marketDataTypeLabel = new Label("CHECKING...");
        panel.addComponent(marketDataTypeLabel);

        // Row 3
        panel.addComponent(new Label("Net Liquidation:").addStyle(SGR.BOLD));
        netLiqLabel = new Label("Loading...");
        panel.addComponent(netLiqLabel);

        panel.addComponent(new Label("Positions / Orders:").addStyle(SGR.BOLD));
        positionsCountLabel = new Label("0");
        ordersCountLabel = new Label("0");
        Panel posOrdPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        posOrdPanel.addComponent(positionsCountLabel);
        posOrdPanel.addComponent(new Label(" pos  /  "));
        posOrdPanel.addComponent(ordersCountLabel);
        posOrdPanel.addComponent(new Label(" orders"));
        panel.addComponent(posOrdPanel);

        // Row 4 — journal summary
        panel.addComponent(new Label("Trades (journal):").addStyle(SGR.BOLD));
        journalTradesLabel = new Label("0 closed");
        panel.addComponent(journalTradesLabel);

        panel.addComponent(new Label("Realized P&L:").addStyle(SGR.BOLD));
        journalPnLLabel = new Label("$0.00");
        panel.addComponent(journalPnLLabel);

        return panel;
    }

    private Panel createStrategyPanel() {
        strategyPanel = new Panel(new GridLayout(3));

        strategyPanel.addComponent(new Label("Strategy").addStyle(SGR.BOLD));
        strategyPanel.addComponent(new Label("Hours (ET)").addStyle(SGR.BOLD));
        strategyPanel.addComponent(new Label("Status").addStyle(SGR.BOLD));

        for (Strategy strategy : strategyRunner.getStrategies()) {
            strategyPanel.addComponent(new Label(strategy.getName()));
            strategyPanel.addComponent(new Label(strategy.getStartTime() + " - " + strategy.getEndTime()));
            Label lbl = new Label(strategyRunner.isRunning() ? "RUNNING" : "STOPPED");
            lbl.setForegroundColor(strategyRunner.isRunning() ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
            strategyPanel.addComponent(lbl);
        }

        if (strategyRunner.getStrategies().isEmpty()) {
            strategyPanel.addComponent(new Label("No strategies registered"));
            strategyPanel.addComponent(new EmptySpace());
            strategyPanel.addComponent(new EmptySpace());
        }

        return strategyPanel;
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

    // -------------------------------------------------------------------------
    // Controls actions
    // -------------------------------------------------------------------------

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
        startStopButton.setLabel(strategyRunner.isRunning() ? "Stop" : "Start");
    }

    private void showPositions() {
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions()
                    .stream().filter(p -> !p.getPos().isZero()).collect(Collectors.toList());

            BasicWindow win = new BasicWindow("Open Positions");
            win.setHints(List.of(Window.Hint.CENTERED));

            Panel grid = new Panel(new GridLayout(4));
            addHeaderCell(grid, "Symbol");
            addHeaderCell(grid, "Qty");
            addHeaderCell(grid, "Avg Cost");
            addHeaderCell(grid, "Account");

            if (positions.isEmpty()) {
                grid.addComponent(new Label("No open positions"));
                for (int i = 0; i < 3; i++) grid.addComponent(new EmptySpace());
            } else {
                for (PositionOutput pos : positions) {
                    grid.addComponent(new Label(pos.getContract().symbol()).addStyle(SGR.BOLD));
                    grid.addComponent(new Label(pos.getPos().toString()));
                    grid.addComponent(new Label(String.format("$%.2f", pos.getAvgCost())));
                    grid.addComponent(new Label(pos.getAccount()));
                }
            }

            Panel main = new Panel(new LinearLayout(Direction.VERTICAL));
            main.addComponent(grid.withBorder(Borders.singleLine()));
            main.addComponent(new EmptySpace());
            main.addComponent(new Button("Close", win::close));
            win.setComponent(main);
            gui.addWindowAndWait(win);

        } catch (Exception e) {
            log.error("Failed to fetch positions", e);
            MessageDialog.showMessageDialog(gui, "Error", "Failed to fetch positions:\n" + e.getMessage());
        }
    }

    private void showOrders() {
        try {
            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();

            BasicWindow win = new BasicWindow("Open Orders");
            win.setHints(List.of(Window.Hint.CENTERED));

            Panel grid = new Panel(new GridLayout(6));
            addHeaderCell(grid, "Symbol");
            addHeaderCell(grid, "Action");
            addHeaderCell(grid, "Qty");
            addHeaderCell(grid, "Type");
            addHeaderCell(grid, "Status");
            addHeaderCell(grid, "Strategy");

            if (orders.isEmpty()) {
                grid.addComponent(new Label("No open orders"));
                for (int i = 0; i < 5; i++) grid.addComponent(new EmptySpace());
            } else {
                for (OrderOutput order : orders) {
                    grid.addComponent(new Label(order.getContract().symbol()).addStyle(SGR.BOLD));

                    String action = order.getOrder().action().name();
                    Label actionLbl = new Label(action);
                    actionLbl.setForegroundColor("BUY".equals(action) ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                    grid.addComponent(actionLbl);

                    grid.addComponent(new Label(order.getOrder().totalQuantity().toString()));
                    grid.addComponent(new Label(order.getOrder().orderType().name()));
                    grid.addComponent(new Label(order.getOrderState().status().name()));

                    String strategy = order.getOrder().orderRef();
                    grid.addComponent(new Label(strategy != null && !strategy.isBlank() ? strategy : "-"));
                }
            }

            Panel main = new Panel(new LinearLayout(Direction.VERTICAL));
            main.addComponent(grid.withBorder(Borders.singleLine()));
            main.addComponent(new EmptySpace());
            main.addComponent(new Button("Close", win::close));
            win.setComponent(main);
            gui.addWindowAndWait(win);

        } catch (Exception e) {
            log.error("Failed to fetch orders", e);
            MessageDialog.showMessageDialog(gui, "Error", "Failed to fetch orders:\n" + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // P&L dialog
    // -------------------------------------------------------------------------

    private static final String   ALL_STRATEGIES = "All Strategies";
    private static final String[] PERIOD_LABELS  = {"Today (1d)", "Last 7 days", "Last 30 days", "Last 90 days"};
    private static final int[]    PERIOD_DAYS    = {1, 7, 30, 90};

    private void showPnL() {
        showPnLDialog(lastStrategyFilter, lastLoadedDays);
    }

    private void showPnLDialog(String strategyFilter, int loadedDays) {
        lastStrategyFilter = strategyFilter;
        lastLoadedDays = loadedDays;

        List<TradeRecord> allTrades = tradeJournal.getCompletedTrades();

        // Build strategy options from loaded trades
        List<String> strategyOptions = new ArrayList<>();
        strategyOptions.add(ALL_STRATEGIES);
        allTrades.stream().map(TradeRecord::getStrategy).distinct().sorted().forEach(strategyOptions::add);

        // Filter
        List<TradeRecord> filtered = allTrades.stream()
                .filter(t -> ALL_STRATEGIES.equals(strategyFilter) || t.getStrategy().equals(strategyFilter))
                .collect(Collectors.toList());

        List<TradeRecord> closingTrades = filtered.stream().filter(TradeRecord::isClosingTrade).collect(Collectors.toList());
        int totalClosed = closingTrades.size();
        int winners = (int) closingTrades.stream().filter(t -> t.getNetPnL() > 0).count();
        double totalNetPnL = closingTrades.stream().mapToDouble(TradeRecord::getNetPnL).sum();
        double totalComm = filtered.stream().mapToDouble(TradeRecord::getCommission).sum();

        BasicWindow win = new BasicWindow("P&L Report");
        win.setHints(List.of(Window.Hint.CENTERED));

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));

        // ── Filter row ────────────────────────────────────────────────────────
        Panel filterRow = new Panel(new LinearLayout(Direction.HORIZONTAL));

        filterRow.addComponent(new Label("Period:"));
        ComboBox<String> periodCombo = new ComboBox<>(PERIOD_LABELS);
        for (int i = 0; i < PERIOD_DAYS.length; i++) {
            if (PERIOD_DAYS[i] == loadedDays) { periodCombo.setSelectedIndex(i); break; }
        }
        filterRow.addComponent(periodCombo);

        filterRow.addComponent(new Label("  Strategy:"));
        ComboBox<String> strategyCombo = new ComboBox<>(strategyOptions.toArray(new String[0]));
        int si = strategyOptions.indexOf(strategyFilter);
        if (si >= 0) strategyCombo.setSelectedIndex(si);
        filterRow.addComponent(strategyCombo);

        // Single Refresh button: fetches from IBKR, waits for completion, then reopens
        filterRow.addComponent(new Button("  Refresh  ", () -> {
            int idx = Math.max(0, periodCombo.getSelectedIndex());
            int days = PERIOD_DAYS[idx];
            String strategy = strategyCombo.getSelectedItem() != null
                    ? strategyCombo.getSelectedItem() : ALL_STRATEGIES;
            win.close();

            // Show a non-blocking loading window while IBKR data arrives
            BasicWindow loadingWin = new BasicWindow("Loading");
            loadingWin.setHints(List.of(Window.Hint.CENTERED));
            Panel loadingPanel = new Panel(new LinearLayout(Direction.VERTICAL));
            loadingPanel.addComponent(new Label(
                    "  Fetching " + PERIOD_LABELS[idx] + " of history from IBKR...  "));
            loadingPanel.addComponent(new EmptySpace());
            loadingPanel.addComponent(new Label("  Please wait.  "));
            loadingWin.setComponent(loadingPanel);
            gui.addWindow(loadingWin);

            CompletableFuture<Void> future = ibkrConnection.reqExecutions(days);
            new Thread(() -> {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("reqExecutions wait timed out or failed: {}", e.getMessage());
                }
                gui.getGUIThread().invokeLater(() -> {
                    loadingWin.close();
                    showPnLDialog(strategy, days);
                });
            }, "PnL-Refresh").start();
        }));

        mainPanel.addComponent(filterRow.withBorder(Borders.singleLine("Filters")));

        // ── Overall banner ────────────────────────────────────────────────────
        if (totalClosed > 0) {
            int winPct = (int) (100.0 * winners / totalClosed);
            Panel banner = new Panel(new LinearLayout(Direction.HORIZONTAL));
            banner.addComponent(new Label(String.format(
                    "  Closed: %d  |  Win Rate: %d/%d (%d%%)  |  Net P&L: ",
                    totalClosed, winners, totalClosed, winPct)));
            Label pnlBanner = new Label(formatPnL(totalNetPnL));
            pnlBanner.setForegroundColor(totalNetPnL >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
            pnlBanner.addStyle(SGR.BOLD);
            banner.addComponent(pnlBanner);
            banner.addComponent(new Label(String.format("  |  Commission: -$%.2f  ", Math.abs(totalComm))));
            mainPanel.addComponent(banner.withBorder(Borders.singleLine("Overview")));
        }

        mainPanel.addComponent(new EmptySpace());

        // ── Strategy summary table ────────────────────────────────────────────
        mainPanel.addComponent(new Label("Strategy Summary").addStyle(SGR.BOLD));
        Map<String, TradeJournal.StrategySummary> summaries = buildSummaries(filtered);

        if (summaries.isEmpty()) {
            mainPanel.addComponent(new Label("  No completed trades for the selected filter."));
        } else {
            Panel summaryGrid = new Panel(new GridLayout(5));
            addHeaderCell(summaryGrid, "Strategy");
            addHeaderCell(summaryGrid, "Trades");
            addHeaderCell(summaryGrid, "Winners");
            addHeaderCell(summaryGrid, "Net P&L");
            addHeaderCell(summaryGrid, "Commission");

            double grandPnL = 0, grandComm = 0;
            int grandTrades = 0, grandWin = 0;
            for (TradeJournal.StrategySummary s : summaries.values()) {
                int wp = s.totalTrades() > 0 ? (int) (100.0 * s.winningTrades() / s.totalTrades()) : 0;
                summaryGrid.addComponent(new Label(s.strategy()));
                summaryGrid.addComponent(new Label(String.valueOf(s.totalTrades())));
                summaryGrid.addComponent(new Label(s.winningTrades() + " (" + wp + "%)"));
                Label netL = new Label(formatPnL(s.totalNetPnL()));
                netL.setForegroundColor(s.totalNetPnL() >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                summaryGrid.addComponent(netL);
                summaryGrid.addComponent(new Label(String.format("-$%.2f", Math.abs(s.totalCommission()))));
                grandPnL += s.totalNetPnL();
                grandComm += s.totalCommission();
                grandTrades += s.totalTrades();
                grandWin += s.winningTrades();
            }

            // Totals row (only meaningful when multiple strategies)
            if (summaries.size() > 1) {
                int gwp = grandTrades > 0 ? (int) (100.0 * grandWin / grandTrades) : 0;
                addBoldCell(summaryGrid, "TOTAL");
                addBoldCell(summaryGrid, String.valueOf(grandTrades));
                addBoldCell(summaryGrid, grandWin + " (" + gwp + "%)");
                Label totalPnL = new Label(formatPnL(grandPnL));
                totalPnL.setForegroundColor(grandPnL >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                totalPnL.addStyle(SGR.BOLD);
                summaryGrid.addComponent(totalPnL);
                addBoldCell(summaryGrid, String.format("-$%.2f", Math.abs(grandComm)));
            }

            mainPanel.addComponent(summaryGrid.withBorder(Borders.singleLine()));
        }

        mainPanel.addComponent(new EmptySpace());

        // ── Trade history ─────────────────────────────────────────────────────
        int entryCount = (int) filtered.stream().filter(t -> !t.isClosingTrade()).count();
        String histTitle = String.format("Trade History  (%d closed, %d entries)",
                closingTrades.size(), entryCount);
        mainPanel.addComponent(new Label(histTitle).addStyle(SGR.BOLD));

        if (filtered.isEmpty()) {
            mainPanel.addComponent(new Label("  No trades for the selected filter."));
        } else {
            Panel histGrid = new Panel(new GridLayout(7));
            addHeaderCell(histGrid, "Date/Time");
            addHeaderCell(histGrid, "Symbol");
            addHeaderCell(histGrid, "Side");
            addHeaderCell(histGrid, "Qty");
            addHeaderCell(histGrid, "Fill $");
            addHeaderCell(histGrid, "P&L");
            addHeaderCell(histGrid, "Strategy");

            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss");
            for (TradeRecord t : filtered) {
                histGrid.addComponent(new Label(t.getTime() != null ? t.getTime().format(timeFmt) : "-"));

                histGrid.addComponent(new Label(t.getSymbol()).addStyle(SGR.BOLD));

                Label sideLbl = new Label(t.getSide());
                sideLbl.setForegroundColor("BOT".equals(t.getSide()) ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                histGrid.addComponent(sideLbl);

                histGrid.addComponent(new Label(String.valueOf((int) t.getShares())));
                histGrid.addComponent(new Label(String.format("$%.2f", t.getFillPrice())));

                if (t.isClosingTrade()) {
                    Label pnlLbl = new Label(formatPnL(t.getNetPnL()));
                    pnlLbl.setForegroundColor(t.getNetPnL() >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
                    histGrid.addComponent(pnlLbl);
                } else {
                    Label entryLbl = new Label("ENTRY");
                    entryLbl.setForegroundColor(TextColor.ANSI.CYAN);
                    histGrid.addComponent(entryLbl);
                }

                histGrid.addComponent(new Label(t.getStrategy()));
            }
            mainPanel.addComponent(histGrid.withBorder(Borders.singleLine()));
        }

        mainPanel.addComponent(new EmptySpace());
        mainPanel.addComponent(new Button("Close", win::close));

        win.setComponent(mainPanel);
        gui.addWindowAndWait(win);
    }

    // -------------------------------------------------------------------------
    // Refresh logic
    // -------------------------------------------------------------------------

    private void refreshUI() {
        refreshStatusPanel();
        startStopButton.setLabel(strategyRunner.isRunning() ? "Stop" : "Start");
    }

    private void refreshStatusPanel() {
        try {
            // Connection state
            IBKRConnection.ConnectionState state = ibkrConnection.getConnectionState();
            switch (state) {
                case CONNECTED    -> { statusLabel.setText("CONNECTED");     statusLabel.setForegroundColor(TextColor.ANSI.GREEN); }
                case CONNECTING   -> { statusLabel.setText("CONNECTING..."); statusLabel.setForegroundColor(TextColor.ANSI.YELLOW); }
                case RECONNECTING -> { statusLabel.setText("RECONNECTING..."); statusLabel.setForegroundColor(TextColor.ANSI.YELLOW); }
                case DISCONNECTED -> { statusLabel.setText("DISCONNECTED");  statusLabel.setForegroundColor(TextColor.ANSI.RED); }
                case FAILED       -> { statusLabel.setText("FAILED");        statusLabel.setForegroundColor(TextColor.ANSI.RED); }
            }

            // Account summary
            List<AccountSummaryOutput> accountSummary = ibkrConnection.reqAccountSummary("NetLiquidation,AccountType");
            for (AccountSummaryOutput s : accountSummary) {
                if ("NetLiquidation".equals(s.getTag())) {
                    netLiqLabel.setText("$" + s.getValue());
                }
                if ("AccountType".equals(s.getTag())) {
                    accountLabel.setText(s.getAccount() + " (" + s.getValue() + ")");
                    boolean isPaper = s.getAccount().startsWith("DU") || s.getAccount().startsWith("DF");
                    tradingModeLabel.setText(isPaper ? "PAPER" : "LIVE");
                    tradingModeLabel.setForegroundColor(isPaper ? TextColor.ANSI.YELLOW : TextColor.ANSI.RED_BRIGHT);
                    tradingModeLabel.setBackgroundColor(isPaper ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE);
                }
            }

            // Positions and orders counts
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            long activePos = positions.stream().filter(p -> !p.getPos().isZero()).count();
            positionsCountLabel.setText(String.valueOf(activePos));

            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();
            ordersCountLabel.setText(String.valueOf(orders.size()));

            // Market data type
            boolean isDelayed = ibkrConnection.isMarketDataDelayed();
            marketDataTypeLabel.setText(ibkrConnection.getMarketDataTypeString());
            marketDataTypeLabel.setForegroundColor(isDelayed ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.GREEN);

            // Journal summary
            List<TradeRecord> closingTrades = tradeJournal.getCompletedTrades().stream()
                    .filter(TradeRecord::isClosingTrade).collect(Collectors.toList());
            int closed = closingTrades.size();
            double netPnL = closingTrades.stream().mapToDouble(TradeRecord::getNetPnL).sum();
            journalTradesLabel.setText(closed + " closed");
            journalPnLLabel.setText(formatPnL(netPnL));
            journalPnLLabel.setForegroundColor(netPnL >= 0 ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);

        } catch (Exception e) {
            statusLabel.setText("ERROR");
            statusLabel.setForegroundColor(TextColor.ANSI.RED);
            log.warn("Failed to refresh status: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Formats a P&L value as "+$116.94" or "-$37.04". Avoids %+$ which is invalid in Java format strings. */
    private static String formatPnL(double value) {
        return String.format("%s$%.2f", value >= 0 ? "+" : "-", Math.abs(value));
    }

    private static void addHeaderCell(Panel panel, String text) {
        panel.addComponent(new Label(text).addStyle(SGR.BOLD).addStyle(SGR.UNDERLINE));
    }

    private static void addBoldCell(Panel panel, String text) {
        panel.addComponent(new Label(text).addStyle(SGR.BOLD));
    }

    /** Builds strategy summaries from an arbitrary list of trades (supports filtered views). */
    private Map<String, TradeJournal.StrategySummary> buildSummaries(List<TradeRecord> trades) {
        Map<String, List<TradeRecord>> byStrategy = new LinkedHashMap<>();
        for (TradeRecord t : trades) byStrategy.computeIfAbsent(t.getStrategy(), k -> new ArrayList<>()).add(t);

        Map<String, TradeJournal.StrategySummary> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<TradeRecord>> e : byStrategy.entrySet()) {
            List<TradeRecord> ts = e.getValue();
            int total   = ts.size();
            int winners = (int) ts.stream().filter(t -> t.isClosingTrade() && t.getNetPnL() > 0).count();
            double netPnL    = ts.stream().filter(TradeRecord::isClosingTrade).mapToDouble(TradeRecord::getNetPnL).sum();
            double commission = ts.stream().mapToDouble(TradeRecord::getCommission).sum();
            result.put(e.getKey(), new TradeJournal.StrategySummary(e.getKey(), total, winners, netPnL, commission));
        }
        return result;
    }

    private void confirmQuit() {
        MessageDialogButton result = MessageDialog.showMessageDialog(
                gui, "Confirm Exit",
                "Are you sure you want to quit?\nThis will stop all strategies and disconnect.",
                MessageDialogButton.Yes, MessageDialogButton.No);
        if (result == MessageDialogButton.Yes) {
            isRunning = false;
            mainWindow.close();
        }
    }

    private void startRefreshTask() {
        refresher.scheduleAtFixedRate(() -> {
            if (isRunning && gui != null) {
                try { gui.getGUIThread().invokeLater(this::refreshUI); }
                catch (Exception e) { log.trace("Refresh failed: {}", e.getMessage()); }
            }
        }, 5, 30, TimeUnit.SECONDS);
    }

    private void shutdown() {
        isRunning = false;
        refresher.shutdown();
        try { screen.stopScreen(); }
        catch (IOException e) { log.error("Error stopping screen", e); }
    }
}
