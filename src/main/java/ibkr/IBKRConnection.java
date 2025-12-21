package ibkr;

import com.ib.client.*;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class IBKRConnection {

    EWrapperImpl eWrapper = new EWrapperImpl();
    EJavaSignal eSignal = new EJavaSignal();
    private EReader reader;


    private EClientSocket client = new EClientSocket( eWrapper, eSignal);

    public void onConnect() throws InterruptedException, ExecutionException {
        client.setConnectOptions("+PACEAPI");
        client.optionalCapabilities("");

        //7497 or 7478
        client.eConnect("127.0.0.1",7497,2);

        if(client.isConnected()){
            System.out.println("SuccessFully Connected to Server"); //also add date time
        }

        // Method 1: Using ExecutorService to create threads
        reader = new EReader(client, eSignal);
        Executor executor = Executors.newFixedThreadPool(2);
        executor.execute(() -> reader.start());
        executor.execute(this::processMessages);

        //Method 2: manual thread creation way
//        reader.start();
//        new Thread (()-> processMessages()).start();
//        reader.start();
//
//        // Start message processing in a separate thread
//        new Thread(() -> {
//            while (client.isConnected()) {
//                eSignal.waitForSignal();
//                try {
//                    reader.processMsgs();
//                } catch (Exception e) {
//                    System.out.println("Error processing messages: " + e.getMessage());
//                }
//            }
//        }).start();
//
//        // Give the reader thread time to initialize
//        Thread.sleep(100);

    }

    private void processMessages() {
        while (client.isConnected()) {
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
//        contract.currency("USD");
//
        //        // Define an actual contract
        Contract contract = new Contract();
        contract.symbol("AAPL");
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");

        client.reqMktData(1, contract, "", false, false, null);
    }
}