package strategy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface Strategy {
    boolean run() throws ExecutionException, InterruptedException, TimeoutException;
}
