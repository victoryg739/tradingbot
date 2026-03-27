package bot;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.AccountSummaryOutput;
import monitoring.MonitoringConfig;
import monitoring.MonitoringServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import risk.Position;
import risk.RiskManager;
import strategy.BullFlagBreakout;
import strategy.LowFloatMomentum;
import strategy.StrategyRunner;
import trade.TradeJournal;
import ui.TradingBotTUI;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class TradingBot {
    private static final Logger log = LoggerFactory.getLogger(TradingBot.class);

    public static void main(String[] args) {
        boolean headless = Arrays.asList(args).contains("--headless");

        log.info("=== Trading Bot Starting {} ===", headless ? "(headless mode)" : "(TUI mode)");

        IBKRConnection ibkrConnection = null;
        StrategyRunner strategyRunner = null;
        MonitoringServer monitor = null;

        try {
            ibkrConnection = new IBKRConnection();
            TradeJournal journal = new TradeJournal();
            ibkrConnection.setTradeJournal(journal);

            MonitoringConfig monConfig = MonitoringConfig.load();
            monitor = new MonitoringServer(monConfig, ibkrConnection, journal);
            ibkrConnection.setMonitor(monitor);
            monitor.start();

            Position position = new Position(ibkrConnection);
            RiskManager riskManager = new RiskManager();

            strategyRunner = new StrategyRunner(ibkrConnection);

            ibkrConnection.onConnect();

            // Load today's execution history into the journal
            ibkrConnection.reqExecutions(1);

            int marketDataType = MarketDataType.REALTIME;
            ibkrConnection.reqMarketDataType(marketDataType);

            // Log critical trading environment info
            String tradingMode = logTradingEnvironment(ibkrConnection, marketDataType);
            monitor.setTradingMode(tradingMode);
            monitor.sendAlert("🤖 Trading bot started (" + tradingMode + " mode)");

            Properties strategyCfg = loadStrategyConfig();

            if (Boolean.parseBoolean(strategyCfg.getProperty("strategy.LowFloatMomentum", "false"))) {
                strategyRunner.addStrategy(new LowFloatMomentum(ibkrConnection, position, riskManager));
                log.info("Strategy registered: LowFloatMomentum");
            } else {
                log.info("Strategy DISABLED (strategies.properties): LowFloatMomentum");
            }

            if (Boolean.parseBoolean(strategyCfg.getProperty("strategy.BullFlagBreakout", "false"))) {
                strategyRunner.addStrategy(new BullFlagBreakout(ibkrConnection, position, riskManager));
                log.info("Strategy registered: BullFlagBreakout");
            } else {
                log.info("Strategy DISABLED (strategies.properties): BullFlagBreakout");
            }

            // Add shutdown hook for graceful shutdown
            final IBKRConnection finalConnection = ibkrConnection;
            final StrategyRunner finalRunner = strategyRunner;
            final MonitoringServer finalMonitor = monitor;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("=== Trading Bot Shutting Down ===");
                if (finalRunner != null) {
                    finalRunner.shutdown();
                }
                if (finalConnection != null) {
                    finalConnection.onDisconnect();
                }
                if (finalMonitor != null) {
                    finalMonitor.stop();
                }
                log.info("=== Trading Bot Stopped ===");
            }));

            if (headless) {
                // Headless mode - start immediately and run until interrupted
                log.info("Starting strategy runner in headless mode...");
                strategyRunner.start();

                // Keep the main thread alive
                Thread.currentThread().join();
            } else {
                // TUI mode - let user control via terminal UI
                log.info("Launching Terminal UI...");
                TradingBotTUI tui = new TradingBotTUI(ibkrConnection, strategyRunner, journal);
                tui.start();
            }

        } catch (InterruptedException e) {
            log.error("Bot interrupted", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("Execution error: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
        }
    }

    private static Properties loadStrategyConfig() {
        Properties props = new Properties();
        try (InputStream is = TradingBot.class.getClassLoader().getResourceAsStream("strategies.properties")) {
            if (is != null) {
                props.load(is);
                log.info("Loaded strategy config from strategies.properties");
            } else {
                log.warn("strategies.properties not found on classpath — all strategies disabled by default");
            }
        } catch (IOException e) {
            log.warn("Failed to load strategies.properties: {} — all strategies disabled by default", e.getMessage());
        }
        return props;
    }

    private static String logTradingEnvironment(IBKRConnection ibkrConnection, int requestedMarketDataType) {
        try {
            List<AccountSummaryOutput> accountSummary = ibkrConnection.reqAccountSummary("AccountType");

            String accountId = "";
            String accountType = "";
            for (AccountSummaryOutput summary : accountSummary) {
                if ("AccountType".equals(summary.getTag())) {
                    accountId = summary.getAccount();
                    accountType = summary.getValue();
                    break;
                }
            }

            // Determine if paper or live based on account ID prefix
            // Paper accounts: DU (demo unlimited), DF (demo funded)
            // Live accounts: U (universal), other prefixes
            boolean isPaper = accountId.startsWith("DU") || accountId.startsWith("DF");
            String tradingMode = isPaper ? "PAPER" : "LIVE";

            String marketDataTypeStr = MarketDataType.getField(requestedMarketDataType);
            boolean isDelayed = ibkrConnection.isMarketDataDelayed();

            log.info("╔════════════════════════════════════════════════════════╗");
            log.info("║              TRADING ENVIRONMENT                       ║");
            log.info("╠════════════════════════════════════════════════════════╣");
            log.info("║  Account:      {} ({})                      ", accountId, accountType);
            log.info("║  Trading Mode: {}{}                         ", tradingMode, isPaper ? "" : " *** REAL MONEY ***");
            log.info("║  Market Data:  {}{}                         ", marketDataTypeStr, isDelayed ? " *** DELAYED ***" : "");
            log.info("╚════════════════════════════════════════════════════════╝");

            if (!isPaper) {
                log.warn(">>> LIVE TRADING ENABLED - REAL MONEY AT RISK <<<");
            }
            if (isDelayed) {
                log.warn(">>> MARKET DATA IS DELAYED - NOT REAL-TIME <<<");
            }

            return tradingMode;
        } catch (Exception e) {
            log.warn("Could not determine trading environment: {}", e.getMessage());
            return "UNKNOWN";
        }
    }
}
