package data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestTracker <T> {
    // A map of reqeuest ID → CompletableFuture (holds a promise that will eventually return data)
    private final ConcurrentHashMap<Integer, CompletableFuture<List<T>>> futures = new ConcurrentHashMap<>();
    // A map of request ID -> List<T> -> Temporarily accumulates incoming data items for each request
    private final ConcurrentHashMap<Integer, List<T>> buffers = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1000);

    public int nextReqId() {
        return nextId.getAndIncrement();
    }

    public void start(int reqId, CompletableFuture<List<T>> future) {
        buffers.put(reqId, new ArrayList<>());
        futures.put(reqId, future);
    }

    public void add(int reqId, T item) {
        List<T> buffer = buffers.get(reqId);
        if (buffer != null) buffer.add(item);
    }

    public void complete(int reqId) {
        CompletableFuture<List<T>> future = futures.remove(reqId);
        List<T> data = buffers.remove(reqId);
        if (future != null && data != null) {
            future.complete(data);
        }
    }
}
