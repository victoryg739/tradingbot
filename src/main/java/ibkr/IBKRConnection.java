package ibkr;

import com.ib.client.*;
import data.RequestTracker;
import ibkr.model.ScanData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class IBKRConnection {

    private EWrapperImpl eWrapper;
    EJavaSignal eSignal = new EJavaSignal();
    private EReader reader;
    private EClientSocket client;
    private RequestTracker<ScanData> marketTracker;

    public IBKRConnection() {
        marketTracker = new RequestTracker<>();

        eWrapper = new EWrapperImpl(marketTracker);
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

    public void reqMarketData(){
        //        Contract c =  new Contract();
//        //TODO: The contract is a class to include combo legs, delta neutral etc
//
//        final String ALL_GENERIC_TICK_TAGS = "100,101,104,106,165,221,232,236,258,293,294,295,318,411,460,619";
//
//        //https://interactivebrokers.github.io/tws-api/tick_types.html
//        //  These are all the generic tick types that IBKR's API supports. IBKR defined this list — it's their official set of optional market data fields.
//        //
//        //  | ID            | Why Included                                |
//        //  |---------------|---------------------------------------------|
//        //  | 100, 101      | Options traders need volume & open interest |
//        //  | 104, 106      | Volatility data for options pricing         |
//        //  | 165           | Misc stats (avg volume) for analysis        |
//        //  | 221           | Mark price for margin calculations          |
//        //  | 232           | 13-week low for technical analysis          |
//        //  | 236           | Shortable shares for short sellers          |
//        //  | 258           | Fundamental data (P/E, EPS) for investors   |
//        //  | 293, 294, 295 | Trade/volume rates for momentum analysis    |
//        //  | 318           | Last RTH trade for overnight gaps           |
//        //  | 411           | Real-time historical volatility             |
//        //  | 460           | Bond factor (for fixed income)              |
//        //  | 619           | IPO price estimates                         |
//
//        //market snapshot -  A market snapshot is a one-time quote instead of continuous streaming data.
//        boolean marketSnapshot = false; //false means streaming
//        boolean regulatorySnapshot = false;
//        List<TagValue> marketDataOptions = new ArrayList<>();
//        client.eConnect("127.0.0.1", 7497, 2);
//        client.reqMktData(1, c, ALL_GENERIC_TICK_TAGS, marketSnapshot, regulatorySnapshot, marketDataOptions);
//
//        // Start the EReader thread - THIS IS CRITICAL
//        final EReader reader = new EReader(client, signal);
//        reader.start();
//

//        // Define an actual contract
//        Contract contract = new Contract();
//        contract.symbol("AAPL");
//        contract.secType("STK");
//        contract.exchange("SMART");
//        contract.currency("USD");.
//
        //        // Define an actual contract
        Contract contract = new Contract();
        contract.symbol("AAPL");
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");

        client.reqMktData(1, contract, "", false, false, null);
    }

    public void reqHistoricalData() {
        /** 1. int reqID -> unique request identifier to match response to find that request
         *  2. Contract -> Object describing the instrument
         *  3. String endDateTime -> "YYYYMMDD HH:MM:SS [TZ]" (TZ optional) or  "" for now
         *  4. String durationStr -> how far back from endDateTime to fetch. format: number + unit (e.g., "30 D", "1 M", "1 Y", "2 W", "3600 S").
         *  5. String barSizeSetting -> the bar (e.g. "1 sec, "1 min", "5 mins", "1 month" ,etc)
         *  6. String whatToShow -> data type to return (e.g "TRADES", "BID", "ASK", "HISTORICAL_VOLATILITY") Use "TRADES" for normal OHLC of trades.
         *  7. int useRTH -> 1 = return data only for Regular Trading Hours (RTH); 0 = return data for all hours (including pre-/post-market).
         *  8. int formatData -> controls the date/time format returned in bars (1 - human-readable date/time strings, 2 - epoch-style)
         *  9. boolean keepUpToDate -> if true, the API attempts to keep the requested bars updated in real time. If false you get a one-shot historical dump.
         *  10. List<TagValue> chartOptions - optional chart options
         */

        Contract contract = new Contract();
        contract.symbol("AAPL");
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        client.reqHistoricalData(1, contract,"","1 Y", "1 min", "ASK", 1,1,false,null);
    }


    public List<ScanData> marketScan(ScannerSubscription scannerSubscription) throws ExecutionException, InterruptedException, TimeoutException {
        // Uncomment to get XML of all valid scanner parameters
        // client.reqScannerParameters();

        int reqId = marketTracker.nextReqId();
        CompletableFuture<List<ScanData>> completableFuture = new CompletableFuture();
        marketTracker.start(reqId,completableFuture);

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

        client.reqScannerSubscription(reqId, scannerSubscription, new ArrayList<>(), new ArrayList<>());
        List<ScanData> results = completableFuture.get(10, TimeUnit.SECONDS);
        client.cancelScannerSubscription(reqId);
        return results;
    }

}