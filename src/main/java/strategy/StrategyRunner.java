package strategy;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Constants;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages all strategies
 * Runs each on its configured interval
 * Handles start/stop lifecycle
 **/
public class StrategyRunner {
    private static final Logger log = LoggerFactory.getLogger(StrategyRunner.class);

    private final ScheduledExecutorService scheduler;
    private final List<Strategy> strategies = new ArrayList<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    // How many strategies can run concurrently
    private final int noOfStrategies = 4;

    @Getter
    private volatile boolean running = false;

    public StrategyRunner() {
        this.scheduler = Executors.newScheduledThreadPool(noOfStrategies);
        log.debug("StrategyRunner initialized with thread pool size={}", noOfStrategies);
    }

    public StrategyRunner addStrategy(Strategy strategy) {
        strategies.add(strategy);
        log.info("Registered strategy: {} (interval={}s, hours={}-{} ET)",
                strategy.getName(),
                strategy.getIntervalSeconds(),
                strategy.getStartTime(),
                strategy.getEndTime());
        return this;
    }

    public void start() {
        if (running) {
            log.warn("StrategyRunner already running - ignoring start request");
            return;
        }

        running = true;
        log.info("Starting StrategyRunner with {} strategies", strategies.size());

        long initialDelay = calculateDelayToNextMinute();
        log.info("Waiting {}ms until next minute boundary", initialDelay);

        for (Strategy strategy : strategies) {
            strategy.onStart();

            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                    () -> executeStrategy(strategy),
                    initialDelay,
                    strategy.getIntervalSeconds() * 1000L,
                    TimeUnit.MILLISECONDS
            );

            scheduledTasks.add(task);
            log.debug("Scheduled strategy: {} with interval={}s", strategy.getName(), strategy.getIntervalSeconds());
        }
    }

    /**
     * Calculate milliseconds until the next minute boundary (e.g., 10:01:00)
     */
    private long calculateDelayToNextMinute() {
        long nowMillis = Instant.now().toEpochMilli();
        long millisInMinute = 60 * 1000L;
        long millisUntilNextMinute = millisInMinute - (nowMillis % millisInMinute);
        return millisUntilNextMinute;
    }

    public void stop() {
        log.info("Stopping StrategyRunner...");
        running = false;

        for (ScheduledFuture<?> task : scheduledTasks) {
            task.cancel(false);
        }
        scheduledTasks.clear();

        for (Strategy strategy : strategies) {
            strategy.onStop();
        }

        log.info("StrategyRunner stopped");
    }

    public void shutdown() {
        log.info("Shutting down StrategyRunner...");
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate gracefully, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Shutdown interrupted, forcing immediate shutdown", e);
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("StrategyRunner shutdown complete");
    }

    private void executeStrategy(Strategy strategy) {
        if (!running) return;

        String strategyName = strategy.getName();
        try {
            LocalTime now = Constants.timeNow();

            if (shouldCloseOrdersAndPositions(now)) {
                log.info("[{}] End of day cutoff reached - closing orders and positions", strategyName);
                strategy.onEndOfDay();
                return;
            }

            if (!isWithinStrategyHours(strategy)) {
                log.debug("[{}] Outside trading hours ({}-{}) - skipping", strategyName,
                        strategy.getStartTime(), strategy.getEndTime());
                return;
            }

            log.debug("[{}] Executing strategy cycle at {}", strategyName, now);
            long startTime = System.currentTimeMillis();

            strategy.run();

            long duration = System.currentTimeMillis() - startTime;
            log.info("[{}] Cycle completed in {}ms", strategyName, duration);

        } catch (Exception e) {
            log.error("[{}] Strategy execution failed: {}", strategyName, e.getMessage(), e);
        }
    }

    private boolean isWithinStrategyHours(Strategy strategy) {
        return !Constants.timeNow().isBefore(strategy.getStartTime()) && Constants.timeNow().isBefore(strategy.getEndTime());
    }

    private boolean shouldCloseOrdersAndPositions(LocalTime now) {
        // Trigger once in a 1-minute window at cutoff time
        return now.isAfter(Constants.INTRADAY_CUTOFF)
                && now.isBefore(Constants.INTRADAY_CUTOFF.plusMinutes(1));
    }

}
