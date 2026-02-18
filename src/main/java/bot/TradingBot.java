package bot;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.AccountSummaryOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import risk.Position;
import risk.RiskManager;
import strategy.LowFloatMomentum;
import strategy.StrategyRunner;
import ui.TradingBotTUI;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TradingBot {
    private static final Logger log = LoggerFactory.getLogger(TradingBot.class);

    public static void main(String[] args) {
        boolean headless = Arrays.asList(args).contains("--headless");

        log.info("=== Trading Bot Starting {} ===", headless ? "(headless mode)" : "(TUI mode)");

        IBKRConnection ibkrConnection = null;
        StrategyRunner strategyRunner = null;

        try {
            ibkrConnection = new IBKRConnection();
            Position position = new Position(ibkrConnection);
            RiskManager riskManager = new RiskManager();

            strategyRunner = new StrategyRunner(ibkrConnection);

            ibkrConnection.onConnect();

            int marketDataType = MarketDataType.REALTIME;
            ibkrConnection.reqMarketDataType(marketDataType);

            // Log critical trading environment info
            logTradingEnvironment(ibkrConnection, marketDataType);

            strategyRunner.addStrategy(new LowFloatMomentum(ibkrConnection, position, riskManager));
            log.info("Strategy registered: LowFloatMomentum");

            // Add shutdown hook for graceful shutdown
            final IBKRConnection finalConnection = ibkrConnection;
            final StrategyRunner finalRunner = strategyRunner;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("=== Trading Bot Shutting Down ===");
                if (finalRunner != null) {
                    finalRunner.shutdown();
                }
                if (finalConnection != null) {
                    finalConnection.onDisconnect();
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
                TradingBotTUI tui = new TradingBotTUI(ibkrConnection, strategyRunner);
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

    private static void logTradingEnvironment(IBKRConnection ibkrConnection, int requestedMarketDataType) {
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

        } catch (Exception e) {
            log.warn("Could not determine trading environment: {}", e.getMessage());
        }
    }
}
