package strategy;

import java.time.LocalTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface Strategy {
    /**
     * Unique name for this strategy
     */
    String getName();

    /**
     * How often to run (in seconds)
     */
    int getIntervalSeconds();

    /**
     * When this strategy should start running (Eastern Time)
     */
    LocalTime getStartTime();

    /**
     * When this strategy should stop running (Eastern Time)
     */
    LocalTime getEndTime();

    /**
     * Execute one cycle of the strategy
     */
    void run() throws Exception;

    /**
     * Called when strategy is started
     */
    default void onStart() {
        System.out.println("Strategy started: " + getName());
    }

    /**
     * Called when strategy is stopped
     */
    default void onStop() {
        System.out.println("Strategy stopped: " + getName());
    }

    default void onEndOfDay() {}
}
