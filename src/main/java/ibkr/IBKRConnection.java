package ibkr;

import com.ib.client.*;
import com.sun.net.httpserver.Request;
import data.RequestTracker;
import data.RequestTrackerManager;
import ibkr.model.HistoricalDataInput;
import ibkr.model.MarketDataInput;
import ibkr.model.ScanData;
import ibkr.model.TickPriceOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class IBKRConnection {

    private EWrapperImpl eWrapper;
    EJavaSignal eSignal = new EJavaSignal();
    private EReader reader;
    private final EClientSocket client;
    private final RequestTrackerManager requestTrackerManager = new RequestTrackerManager();

    public IBKRConnection() {
        eWrapper = new EWrapperImpl(requestTrackerManager);
        client = new EClientSocket( eWrapper, eSignal);

    }

    public void onConnect() throws InterruptedException, ExecutionException {
        client.setConnectOptions("+PACEAPI");
        client.optionalCapabilities("");

        //7497 or 7478
        client.eConnect("127.0.0.1",7497,2);

        if(client.isConnected()){
            System.out.println("SuccessFully Connected to Server"); //also add date time
        }

        reader = new EReader(client, eSignal);

//        // Method 1: Using ExecutorService to create threads -> should use for short running task however our tasks are indefinite until the connection is ending
//        Executor executor = Executors.newFixedThreadPool(2);
//        // Thread 1: Producer -> continuously read raw bytes from the TCP socket
//        executor.execute(() -> reader.start());
//        //Thread 2: Consumer -> parses bytes into messages and triggers the callbacks
//        executor.execute(this::processMessages);

        //Method 2: manual thread creation way
        // Thread 1: Producer -> continuously read raw bytes from the TCP socket
        reader.start();

        //Thread 2: Consumer -> parses bytes into messages and triggers the callbacks
        new Thread(() -> {
            while (client.isConnected()) {
                eSignal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    System.out.println("Error processing messages: " + e.getMessage());
                }
            }
        }).start();

        // Give the reader thread time to initialize
        Thread.sleep(100);

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
        // disconnect from TWS
//        m_disconnectInProgress = true;
        client.eDisconnect();
    }

    public void reqMarketDataType(int marketDataType){
        client.reqMarketDataType(marketDataType);
    }

    public List<TickPriceOutput> reqMarketData(MarketDataInput marketDataInput) throws ExecutionException, InterruptedException, TimeoutException {
        //Note: marketdata returns tick data and this tickData will go on forever as it is streaming
        // For know the implementation is we just get one delayed price tick data will be sufficient
        //TODO: In the future we can try to process on every tick data

        RequestTracker<TickPriceOutput> tickPriceTracker = requestTrackerManager.getTracker(TickPriceOutput.class);
        int reqId = tickPriceTracker.nextReqId();
        CompletableFuture<List<TickPriceOutput>> completableFuture = new CompletableFuture<>();

        tickPriceTracker.start(reqId, completableFuture);
        client.reqMktData(reqId
                , marketDataInput.getContract()
                , marketDataInput.getGenericTickList()
                , marketDataInput.isSnapshot()
                , marketDataInput.isRegulatorySnapshot()
                , marketDataInput.getTagValues());
        List<TickPriceOutput> result = completableFuture.get(10, TimeUnit.SECONDS);
        //we get one tick and we stop now
        client.cancelMktData(reqId);
        return result;
    }

    public List<Bar> reqHistoricalData(HistoricalDataInput historicalDataInput) throws ExecutionException, InterruptedException, TimeoutException {
        RequestTracker<Bar> historicalTracker = requestTrackerManager.getTracker(Bar.class);
        int reqId = historicalTracker.nextReqId();
        CompletableFuture<List<Bar>> completableFuture = new CompletableFuture<>();
        historicalTracker.start(reqId,completableFuture);
        //        Contract contract = new Contract();
//        contract.symbol("SMCI");
//        contract.secType("STK");
//        contract.exchange("SMART");
//        contract.currency("USD");
        //        client.reqHistoricalData(1, contract,"","2 D", "1 min", "TRADES", 1,1,false,null);
        client.reqHistoricalData(1, historicalDataInput.getContract()
                , historicalDataInput.getEndDateTime()
                , historicalDataInput.getDurationStr()
                , historicalDataInput.getBarSize().toString()
                , historicalDataInput.getWhatToShow().toString()
                , historicalDataInput.getUseRth().ordinal()
                , historicalDataInput.getFormatData().getValue()
                , historicalDataInput.isKeepUpToDate()
                , historicalDataInput.getChartOptions());

        return completableFuture.get(10, TimeUnit.SECONDS);
    }


    public List<ScanData> marketScan(ScannerSubscription scannerSubscription, List<TagValue> filterOptions) throws ExecutionException, InterruptedException, TimeoutException {
        // Uncomment to get XML of all valid scanner parameters
        // client.reqScannerParameters();
        RequestTracker<ScanData> scanDataTracker = requestTrackerManager.getTracker(ScanData.class);
        int reqId = scanDataTracker.nextReqId();
        CompletableFuture<List<ScanData>> completableFuture = new CompletableFuture<>();
        scanDataTracker.start(reqId,completableFuture);

//        ScannerSubscription scannerSubscription = new ScannerSubscription();
//
//        // Core scan definition (required)
//        scannerSubscription.instrument("STK");
//        scannerSubscription.locationCode("STK.US.MAJOR");
//        scannerSubscription.scanCode("TOP_PERC_GAIN");  // The ranking algorithm
//
//        // Price filters
//        scannerSubscription.abovePrice(10.0);           // Only stocks above $10
//        scannerSubscription.belowPrice(500.0);          // Only stocks below $500
//
//        // Volume filters
//        scannerSubscription.aboveVolume(1);
////        scannerSubscription.aboveVolume(1000000);       // Min 1M shares traded today
//        // scannerSubscription.averageOptionVolumeAbove(5000);  // For options activity
//
//        // Market cap filters (in millions)
//        scannerSubscription.marketCapAbove(1000.0);     // Only large-cap (>$1B market cap)
//        // scannerSubscription.marketCapBelow(10000.0); // Max $10B market cap
//
//        // Additional filters
//        // scannerSubscription.couponRateAbove(5.0);    // For bonds
//        // scannerSubscription.moodyRatingAbove("Baa"); // Credit rating filters
//        // scannerSubscription.stockTypeFilter("ALL");  // "CORP" or "ADR" or "ETF"

        client.reqScannerSubscription(reqId, scannerSubscription, new ArrayList<>(), filterOptions);
        List<ScanData> results = completableFuture.get(10, TimeUnit.SECONDS);
        client.cancelScannerSubscription(reqId);
        return results;
    }

}