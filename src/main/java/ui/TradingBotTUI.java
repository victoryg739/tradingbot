package ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import ibkr.IBKRConnection;
import ibkr.model.AccountSummaryOutput;
import ibkr.model.PositionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import strategy.Strategy;
import strategy.StrategyRunner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TradingBotTUI {
    private static final Logger log = LoggerFactory.getLogger(TradingBotTUI.class);

    private final IBKRConnection ibkrConnection;
    private final StrategyRunner strategyRunner;

    private Terminal terminal;
    private Screen screen;
    private MultiWindowTextGUI gui;
    private BasicWindow mainWindow;

    // UI Components that need updating
    private Label statusLabel;
    private Label accountLabel;
    private Label pnlLabel;
    private Label positionsCountLabel;
    private Panel strategyPanel;
    private Button startStopButton;

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isRunning = true;

    public TradingBotTUI(IBKRConnection ibkrConnection, StrategyRunner strategyRunner) {
        this.ibkrConnection = ibkrConnection;
        this.strategyRunner = strategyRunner;
    }

    public void start() throws IOException {
        terminal = new DefaultTerminalFactory().createTerminal();
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

    private void refreshUI() {
        refreshStatusPanel();
        refreshStrategyPanel();
    }

    private void refreshStatusPanel() {
        try {
            // Get account info
            List<AccountSummaryOutput> accountSummary = ibkrConnection.reqAccountSummary("NetLiquidation,AccountType");

            for (AccountSummaryOutput summary : accountSummary) {
                if ("NetLiquidation".equals(summary.getTag())) {
                    pnlLabel.setText("$" + summary.getValue());
                }
                if ("AccountType".equals(summary.getTag())) {
                    accountLabel.setText(summary.getAccount() + " (" + summary.getValue() + ")");
                }
            }

            // Get positions count
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            long activePositions = positions.stream().filter(p -> !p.getPos().isZero()).count();
            positionsCountLabel.setText(String.valueOf(activePositions));

        } catch (Exception e) {
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
