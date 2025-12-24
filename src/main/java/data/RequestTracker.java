package data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestTracker <T> {
    private final ConcurrentHashMap<Integer, CompletableFuture<List<T>>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<T>> buffers = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1000);

    public int nextReqId() {
        return nextId.getAndIncrement();
    }

    public void start(int reqId, CompletableFuture<List<T>> future) {
        futures.put(reqId, future);
        buffers.put(reqId, new ArrayList<>());
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
