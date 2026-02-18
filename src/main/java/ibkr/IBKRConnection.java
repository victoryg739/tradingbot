package ibkr;

import bot.TradingBot;
import com.ib.client.*;
import data.RequestTracker;
import data.RequestTrackerManager;
import ibkr.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Constants;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class IBKRConnection {

    private EWrapperImpl eWrapper;
    EJavaSignal eSignal = new EJavaSignal();
    private EReader reader;
    private final EClientSocket client;
    private final RequestTrackerManager requestTrackerManager = new RequestTrackerManager();
    private static final Logger log = LoggerFactory.getLogger(IBKRConnection.class);

    // Connection state management
    public enum ConnectionState {
        DISCONNECTED,    // Not connected
        CONNECTING,      // Initial connection in progress
        CONNECTED,       // Fully connected and operational
        RECONNECTING,    // Lost connection, attempting to reconnect
        FAILED          // Reconnection failed permanently
    }

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int[] RECONNECT_DELAYS_MS = {1000, 2000, 4000, 8000, 16000, 30000};
    private volatile boolean manualDisconnect = false;

    public IBKRConnection() {
        eWrapper = new EWrapperImpl(requestTrackerManager, this);
        client = new EClientSocket( eWrapper, eSignal);
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public boolean isConnected() {
        return client != null && client.isConnected() &&
               connectionState == ConnectionState.CONNECTED;
    }

    public void onConnect() throws InterruptedException, ExecutionException {
        connectionState = ConnectionState.CONNECTING;
        manualDisconnect = false;

        String host = "127.0.0.1";
//        int port = 7497; // TWS Port: 7497=paper

        int port = 4002;  // IB Gateway: 4002=paper, 4001=live (TWS: 7497=paper, 7496=live)

        int clientId = 2;

        log.info("Attempting to connect to IB Gateway at {}:{} with clientId={}", host, port, clientId);

        client.setConnectOptions("+PACEAPI");
        client.optionalCapabilities("");

        //7497 for paper trading, 7496 for live
        //TODO: Design it to be more robust maybe with a configuration file
        client.eConnect(host, port, clientId);

        if (client.isConnected()) {
            log.info("Successfully connected to TWS at {}:{}", host, port);
        } else {
            log.error("Failed to connect to TWS at {}:{}. Possible causes: " +
                    "1) TWS/IB Gateway not running, " +
                    "2) API connections not enabled in TWS settings, " +
                    "3) Wrong port (7497=paper, 7496=live), " +
                    "4) Client ID {} already in use", host, port, clientId);
            throw new ExecutionException("Failed to connect to TWS", null);
        }

        reader = new EReader(client, eSignal);

        // Thread 1: Producer -> continuously read raw bytes from the TCP socket
        reader.start();

        // Thread 2: Consumer -> parses bytes into messages and triggers the callbacks
        new Thread(() -> {
            log.debug("Message processing thread started");
            while (client.isConnected()) {
                eSignal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("Error processing TWS messages: {}", e.getMessage(), e);
                }
            }
            log.warn("Message processing thread exiting - client disconnected");
        }, "TWS-MessageProcessor").start();

        // Give the reader thread time to initialize
        Thread.sleep(100);

        // wait for orderId to be initialized
        log.debug("Waiting for initial order ID from TWS...");
        eWrapper.waitForInitOrderId();
        log.debug("Initial order ID received");

        connectionState = ConnectionState.CONNECTED;
        log.info("Connection fully established, state: {}", connectionState);
    }

    private void processMessages() {
        while (client.isConnected()) {
            //wait for Producer thread signal
            eSignal.waitForSignal();
            try {
                reader.processMsgs();
            }catch (Exception e){
                System.out.println("Error processing messages: " + e.getMessage());
            }
        }
    }

    public void onDisconnect() {
        log.info("Disconnecting from TWS...");
        manualDisconnect = true;
        connectionState = ConnectionState.DISCONNECTED;
        client.eDisconnect();
        log.info("Disconnected from TWS");
    }

    /**
     * Called when connection loss is detected.
     * Initiates reconnection sequence unless it was a manual disconnect.
     */
    public void handleConnectionLoss() {
        if (manualDisconnect) {
            log.info("Ignoring connection loss - was manual disconnect");
            return;
        }

        if (connectionState == ConnectionState.RECONNECTING) {
            log.debug("Already reconnecting, ignoring duplicate notification");
            return;
        }

        log.warn("Connection loss detected, initiating reconnection sequence");
        connectionState = ConnectionState.RECONNECTING;
        reconnectAttempts.set(0);

        // Start reconnection in separate thread (don't block callback)
        new Thread(this::attemptReconnection, "IBKR-Reconnect").start();
    }

    /**
     * Attempts to reconnect with exponential backoff.
     * Runs in dedicated thread to avoid blocking.
     */
    private void attemptReconnection() {
        while (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
            int attempt = reconnectAttempts.incrementAndGet();

            // Check if outside market hours - don't waste efforts
            LocalTime now = Constants.timeNow();
            if (now.isBefore(LocalTime.of(4, 0)) || now.isAfter(LocalTime.of(20, 0))) {
                log.warn("Outside extended market hours (4 AM - 8 PM ET), stopping reconnection attempts");
                connectionState = ConnectionState.FAILED;
                return;
            }

            // Calculate delay with exponential backoff
            int delayIndex = Math.min(attempt - 1, RECONNECT_DELAYS_MS.length - 1);
            int delayMs = RECONNECT_DELAYS_MS[delayIndex];

            log.info("Reconnection attempt {}/{} in {} seconds",
                attempt, MAX_RECONNECT_ATTEMPTS, delayMs / 1000);

            try {
                Thread.sleep(delayMs);

                // Clean up old connection
                if (client.isConnected()) {
                    client.eDisconnect();
                    Thread.sleep(500);  // Brief pause for clean disconnect
                }

                // Attempt reconnection
                log.info("Attempting to reconnect to IB Gateway...");
                onConnect();  // Uses existing connection logic

                // If we get here, connection succeeded
                log.info("Reconnection successful on attempt {}", attempt);
                reconnectAttempts.set(0);
                return;

            } catch (Exception e) {
                log.error("Reconnection attempt {} failed: {}", attempt, e.getMessage());

                if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
                    log.error("Maximum reconnection attempts ({}) reached, giving up",
                        MAX_RECONNECT_ATTEMPTS);
                    connectionState = ConnectionState.FAILED;
                    return;
                }
            }
        }
    }

    public void reqMarketDataType(int marketDataType) {
        log.debug("Setting market data type to {}", marketDataType);
        client.reqMarketDataType(marketDataType);
    }

    public String getMarketDataTypeString() {
        return eWrapper.getMarketDataTypeString();
    }

    public boolean isMarketDataDelayed() {
        return eWrapper.isMarketDataDelayed();
    }

    public List<TickPriceOutput> reqMarketData(MarketDataInput marketDataInput) throws ExecutionException, InterruptedException, TimeoutException {
        String symbol = marketDataInput.getContract().symbol();
        log.debug("[{}] Requesting market data (snapshot={})", symbol, marketDataInput.isSnapshot());

        RequestTracker<TickPriceOutput> tickPriceTracker = requestTrackerManager.getTracker(TickPriceOutput.class);
        int reqId = tickPriceTracker.nextReqId();
        CompletableFuture<List<TickPriceOutput>> completableFuture = new CompletableFuture<>();

        tickPriceTracker.start(reqId, completableFuture);

        client.reqMktData(reqId,
                marketDataInput.getContract(),
                marketDataInput.getGenericTickList(),
                marketDataInput.isSnapshot(),
                marketDataInput.isRegulatorySnapshot(),
                marketDataInput.getTagValues());

        try {
            List<TickPriceOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
            log.debug("[{}] Received {} tick prices", symbol, result.size());
            return result;
        } catch (TimeoutException e) {
            tickPriceTracker.timeout(reqId);
            log.warn("[{}] Market data request timed out after 10 seconds", symbol);
            throw e;
        }
    }

    public List<Bar> reqHistoricalData(HistoricalDataInput historicalDataInput) throws ExecutionException, InterruptedException, TimeoutException {
        String symbol = historicalDataInput.getContract().symbol();
        log.debug("[{}] Requesting historical data: duration={}, barSize={}",
                symbol, historicalDataInput.getDurationStr(), historicalDataInput.getBarSize());

        RequestTracker<Bar> historicalTracker = requestTrackerManager.getTracker(Bar.class);
        int reqId = historicalTracker.nextReqId();
        CompletableFuture<List<Bar>> completableFuture = new CompletableFuture<>();
        historicalTracker.start(reqId, completableFuture);

        client.reqHistoricalData(reqId, historicalDataInput.getContract(),
                historicalDataInput.getEndDateTime(),
                historicalDataInput.getDurationStr(),
                historicalDataInput.getBarSize().toString(),
                historicalDataInput.getWhatToShow().toString(),
                historicalDataInput.getUseRth().ordinal(),
                historicalDataInput.getFormatData().getValue(),
                historicalDataInput.isKeepUpToDate(),
                historicalDataInput.getChartOptions());

        try {
            List<Bar> result = completableFuture.get(10, TimeUnit.SECONDS);
            log.debug("[{}] Received {} historical bars", symbol, result.size());
            return result;
        } catch (TimeoutException e) {
            historicalTracker.timeout(reqId);
            log.warn("[{}] Historical data request timed out after 10 seconds", symbol);
            throw e;
        }
    }

    public ContractDetails reqContractDetails(Contract contract) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("[{}] Requesting contract details", contract.symbol());

        RequestTracker<ContractDetails> contractDetailsTracker = requestTrackerManager.getTracker(ContractDetails.class);
        int reqId = contractDetailsTracker.nextReqId();
        CompletableFuture<List<ContractDetails>> completableFuture = new CompletableFuture<>();
        contractDetailsTracker.start(reqId, completableFuture);

        client.reqContractDetails(reqId, contract);

        List<ContractDetails> contractDetails;
        try {
            contractDetails = completableFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            contractDetailsTracker.timeout(reqId);
            log.warn("[{}] Contract details request timed out after 10 seconds", contract.symbol());
            throw e;
        }

        if (contractDetails == null || contractDetails.isEmpty()) {
            log.error("[{}] No contract details found", contract.symbol());
            throw new IllegalStateException("No contract details found for: " + contract.symbol());
        }

        log.debug("[{}] Contract details received (conId={})", contract.symbol(), contractDetails.getFirst().conid());
        return contractDetails.getFirst();
    }

    public List<ScanData> marketScan(ScannerSubscription scannerSubscription, List<TagValue> filterOptions) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Running market scan: code={}, rows={}", scannerSubscription.scanCode(), scannerSubscription.numberOfRows());

        RequestTracker<ScanData> scanDataTracker = requestTrackerManager.getTracker(ScanData.class);
        int reqId = scanDataTracker.nextReqId();
        CompletableFuture<List<ScanData>> completableFuture = new CompletableFuture<>();
        scanDataTracker.start(reqId, completableFuture);

        client.reqScannerSubscription(reqId, scannerSubscription, new ArrayList<>(), filterOptions);
        List<ScanData> results;
        try {
            results = completableFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            scanDataTracker.timeout(reqId);
            log.warn("Market scan timed out after 10 seconds");
            client.cancelScannerSubscription(reqId);
            throw e;
        }
        client.cancelScannerSubscription(reqId);

        log.debug("Market scan complete: {} results", results.size());
        return results;
    }

    public void placeOrder(Contract contract, Order order) {
        int orderId = eWrapper.getAndIncrementOrderId();
        log.info("Placing order: orderId={}, symbol={}, action={}, qty={}, type={}, price={}",
                orderId, contract.symbol(), order.action(), order.totalQuantity(),
                order.orderType(), order.lmtPrice());

        client.placeOrder(orderId, contract, order);
        log.debug("Order submitted to TWS: orderId={}", orderId);
    }

    public void placeBracketOrders(Contract contract, Order parentOrder, Order childOrder1, Order childOrder2) {
        // Atomically reserve 3 sequential order IDs to prevent race conditions
        // This ensures bracket orders maintain proper parent-child relationships
        int parentOrderId = eWrapper.reserveOrderIds(3);
        int childOrderId1 = parentOrderId + 1;
        int childOrderId2 = parentOrderId + 2;

        log.info("Placing bracket order for {}: parentId={}, takeProfitId={}, stopLossId={}",
                contract.symbol(), parentOrderId, childOrderId1, childOrderId2);
        log.info("Bracket details - Parent: {} {} @ {}, TakeProfit: @ {}, StopLoss: @ {}",
                parentOrder.action(), parentOrder.totalQuantity(), parentOrder.lmtPrice(),
                childOrder1.lmtPrice(), childOrder2.auxPrice());

        childOrder1.parentId(parentOrderId);
        childOrder2.parentId(parentOrderId);

        client.placeOrder(parentOrderId, contract, parentOrder);
        client.placeOrder(childOrderId1, contract, childOrder1);
        client.placeOrder(childOrderId2, contract, childOrder2);

        log.info("Bracket orders submitted to TWS for {}", contract.symbol());
    }

    public List<PositionOutput> reqPositions() throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Requesting all positions...");
        RequestTracker<PositionOutput> positionTracker = requestTrackerManager.getTracker(PositionOutput.class);
        CompletableFuture<List<PositionOutput>> completableFuture = new CompletableFuture<>();
        positionTracker.start(Constants.POSITIONS_REQ_ID, completableFuture);

        client.reqPositions();

        try {
            List<PositionOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
            log.debug("Received {} positions", result.size());
            return result;
        } catch (TimeoutException e) {
            positionTracker.timeout(Constants.POSITIONS_REQ_ID);
            log.warn("Positions request timed out after 10 seconds");
            throw e;
        }
    }

    public List<OrderOutput> reqAllOpenOrder() throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Requesting all open orders...");
        RequestTracker<OrderOutput> orderTracker = requestTrackerManager.getTracker(OrderOutput.class);
        CompletableFuture<List<OrderOutput>> completableFuture = new CompletableFuture<>();
        orderTracker.start(Constants.OPEN_ORDERS_REQ_ID, completableFuture);

        client.reqAllOpenOrders();

        try {
            List<OrderOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
            log.debug("Received {} open orders", result.size());
            return result;
        } catch (TimeoutException e) {
            orderTracker.timeout(Constants.OPEN_ORDERS_REQ_ID);
            log.warn("Open orders request timed out after 10 seconds");
            throw e;
        }
    }

    public List<AccountSummaryOutput> reqAccountSummary(String tags) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Requesting account summary for tags: {}", tags);
        RequestTracker<AccountSummaryOutput> accountSummaryTracker = requestTrackerManager.getTracker(AccountSummaryOutput.class);
        int reqId = accountSummaryTracker.nextReqId();
        CompletableFuture<List<AccountSummaryOutput>> completableFuture = new CompletableFuture<>();
        accountSummaryTracker.start(reqId, completableFuture);

        client.reqAccountSummary(reqId, "All", tags);
        List<AccountSummaryOutput> result;
        try {
            result = completableFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            accountSummaryTracker.timeout(reqId);
            log.warn("Account summary request timed out after 10 seconds");
            client.cancelAccountSummary(reqId);
            throw e;
        }
        client.cancelAccountSummary(reqId);

        log.debug("Received {} account summary entries", result.size());
        return result;
    }

    public void closeAllOrders() {
        log.warn("Cancelling ALL open orders via global cancel");
        client.reqGlobalCancel(new OrderCancel());
        log.info("Global cancel request sent to TWS");
    }

}