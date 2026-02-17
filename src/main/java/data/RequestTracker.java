package data;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestTracker <T> {
    // A map of reqeuest ID → CompletableFuture (holds a promise that will eventually return data)
    private final ConcurrentHashMap<Integer, CompletableFuture<List<T>>> futures = new ConcurrentHashMap<>();
    // A map of request ID -> List<T> -> Temporarily accumulates incoming data items for each request
    private final ConcurrentHashMap<Integer, List<T>> buffers = new ConcurrentHashMap<>();
    // A map of request ID -> timestamp to track when request started (for timeout cleanup)
    private final ConcurrentHashMap<Integer, Long> startTimes = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1000);

    public int nextReqId() {
        return nextId.getAndIncrement();
    }

    public void start(int reqId, CompletableFuture<List<T>> future) {
        buffers.put(reqId, new CopyOnWriteArrayList<>());
        futures.put(reqId, future);
        startTimes.put(reqId, System.currentTimeMillis());
    }

    public void add(int reqId, T item) {
        List<T> buffer = buffers.get(reqId);
        if (buffer != null) buffer.add(item);
    }

    public void complete(int reqId) {
        CompletableFuture<List<T>> future = futures.remove(reqId);
        List<T> data = buffers.remove(reqId);
        startTimes.remove(reqId);
        if (future != null && data != null && !future.isDone()) {
            future.complete(data);
        }
    }

    /**
     * Called when a request times out to clean up resources and prevent memory leaks.
     * Removes the request from all tracking maps and completes the future exceptionally.
     *
     * @param reqId The request ID that timed out
     */
    public void timeout(int reqId) {
        CompletableFuture<List<T>> future = futures.remove(reqId);
        buffers.remove(reqId);
        startTimes.remove(reqId);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new TimeoutException("Request " + reqId + " timed out after 10 seconds"));
        }
    }
}
