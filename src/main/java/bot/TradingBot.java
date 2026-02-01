package bot;

import com.ib.client.*;
import ibkr.IBKRConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import risk.Position;
import risk.RiskManager;
import strategy.LowFloatMomentum;
import strategy.StrategyRunner;
import ui.TradingBotTUI;

import java.util.Arrays;
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

            strategyRunner = new StrategyRunner();

            ibkrConnection.onConnect();

            int marketDataType = MarketDataType.REALTIME;
            ibkrConnection.reqMarketDataType(marketDataType);
            log.debug("Market data type set to {}", MarketDataType.getField(marketDataType));

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
}
