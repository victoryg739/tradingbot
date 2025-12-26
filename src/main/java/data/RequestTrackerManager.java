package data;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RequestTrackerManager {
    private final Map<Class<?> , RequestTracker<?>> trackers =  new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    // <T> method level type that returns a RequestTracker obj of Type T and takes in a Class of Type T as well
    public <T> RequestTracker<T> getTracker(Class<T> dataType) {
        return (RequestTracker<T>) trackers.computeIfAbsent(dataType , i -> new RequestTracker<>());
    }
}
