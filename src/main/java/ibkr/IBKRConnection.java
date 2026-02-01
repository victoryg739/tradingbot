package ibkr;

import bot.TradingBot;
import com.ib.client.*;
import data.RequestTracker;
import data.RequestTrackerManager;
import ibkr.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class IBKRConnection {

    private EWrapperImpl eWrapper;
    EJavaSignal eSignal = new EJavaSignal();
    private EReader reader;
    private final EClientSocket client;
    private final RequestTrackerManager requestTrackerManager = new RequestTrackerManager();
    private static final Logger log = LoggerFactory.getLogger(IBKRConnection.class);

    public IBKRConnection() {
        eWrapper = new EWrapperImpl(requestTrackerManager);
        client = new EClientSocket( eWrapper, eSignal);
    }

    public void onConnect() throws InterruptedException, ExecutionException {
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
        client.eDisconnect();
        log.info("Disconnected from TWS");
    }

    public void reqMarketDataType(int marketDataType) {
        log.debug("Setting market data type to {}", marketDataType);
        client.reqMarketDataType(marketDataType);
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

        List<TickPriceOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
        log.debug("[{}] Received {} tick prices", symbol, result.size());
        return result;
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

        List<Bar> result = completableFuture.get(10, TimeUnit.SECONDS);
        log.debug("[{}] Received {} historical bars", symbol, result.size());
        return result;
    }

    public ContractDetails reqContractDetails(Contract contract) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("[{}] Requesting contract details", contract.symbol());

        RequestTracker<ContractDetails> contractDetailsTracker = requestTrackerManager.getTracker(ContractDetails.class);
        int reqId = contractDetailsTracker.nextReqId();
        CompletableFuture<List<ContractDetails>> completableFuture = new CompletableFuture<>();
        contractDetailsTracker.start(reqId, completableFuture);

        client.reqContractDetails(reqId, contract);

        List<ContractDetails> contractDetails = completableFuture.get(10, TimeUnit.SECONDS);

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
        List<ScanData> results = completableFuture.get(10, TimeUnit.SECONDS);
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

        List<PositionOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
        log.debug("Received {} positions", result.size());
        return result;
    }

    public List<OrderOutput> reqAllOpenOrder() throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Requesting all open orders...");
        RequestTracker<OrderOutput> orderTracker = requestTrackerManager.getTracker(OrderOutput.class);
        CompletableFuture<List<OrderOutput>> completableFuture = new CompletableFuture<>();
        orderTracker.start(Constants.OPEN_ORDERS_REQ_ID, completableFuture);

        client.reqAllOpenOrders();

        List<OrderOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
        log.debug("Received {} open orders", result.size());
        return result;
    }

    public List<AccountSummaryOutput> reqAccountSummary(String tags) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("Requesting account summary for tags: {}", tags);
        RequestTracker<AccountSummaryOutput> accountSummaryTracker = requestTrackerManager.getTracker(AccountSummaryOutput.class);
        int reqId = accountSummaryTracker.nextReqId();
        CompletableFuture<List<AccountSummaryOutput>> completableFuture = new CompletableFuture<>();
        accountSummaryTracker.start(reqId, completableFuture);

        client.reqAccountSummary(reqId, "All", tags);
        List<AccountSummaryOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
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