package bot;

import com.ib.client.*;
import ibkr.IBKRConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import risk.Position;
import risk.RiskManager;
import strategy.LowFloatMomentum;
import strategy.StrategyRunner;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class TradingBot {
    private static final Logger log = LoggerFactory.getLogger(TradingBot.class);

    public static void main(String[] args) {
        log.info("=== Trading Bot Starting ===");

        IBKRConnection ibkrConnection = null;
        StrategyRunner strategyRunner = null;

        try {
            ibkrConnection = new IBKRConnection();
            Position position = new Position(ibkrConnection);
            RiskManager riskManager = new RiskManager();

            strategyRunner = new StrategyRunner();

            ibkrConnection.onConnect();

            int marketDataType = MarketDataType.DELAYED;
            ibkrConnection.reqMarketDataType(marketDataType);
            log.debug("Market data type set to {}", MarketDataType.getField(marketDataType));

            strategyRunner.addStrategy(new LowFloatMomentum(ibkrConnection, position, riskManager));
            log.info("Strategy registered: LowFloatMomentum");

            log.info("Starting strategy runner...");
            strategyRunner.start();
            
        } catch (InterruptedException e) {
            log.error("Bot interrupted during startup", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("Execution error during startup: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during startup: {}", e.getMessage(), e);
        }


        //TODO: Check this function here
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
    }
}
