package data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RequestTrackerManager {
    private static final Logger log = LoggerFactory.getLogger(RequestTrackerManager.class);
    private final Map<Class<?> , RequestTracker<?>> trackers =  new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    // <T> method level type that returns a RequestTracker obj of Type T and takes in a Class of Type T as well
    public <T> RequestTracker<T> getTracker(Class<T> dataType) {
        return (RequestTracker<T>) trackers.computeIfAbsent(dataType , i -> new RequestTracker<>());
    }

    /**
     * Cancels all pending requests across all trackers.
     * Called when connection is lost.
     */
    public void cancelAllPending(String reason) {
        log.warn("Cancelling all pending requests: {}", reason);

        for (RequestTracker<?> tracker : trackers.values()) {
            tracker.cancelAll(reason);
        }
    }
}
