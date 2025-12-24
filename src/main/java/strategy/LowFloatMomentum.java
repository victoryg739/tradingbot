package strategy;

import com.ib.client.Bar;
import com.ib.client.ScannerSubscription;
import ibkr.IBKRConnection;
import ibkr.model.ScanData;
//import data.MarketDataService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class LowFloatMomentum implements Strategy{
//    private final MarketDataService marketData;
    List<ScanData> scanData;
    IBKRConnection ibkrConnection;
//    public LowFloatMomentum (List<ScanData> scanData) {
//        this.scanData = scanData;
//    }
public LowFloatMomentum (IBKRConnection ibkrConnection) {
    this.ibkrConnection = ibkrConnection;
}

    @Override
    public boolean run() throws ExecutionException, InterruptedException, TimeoutException {
//        System.out.println(scanData);
//        ibkrConnection.marketScan();

//        for(ScanData s : scanData){
//            if(s.getContractDetails().contract().symbol().equals("ALB")){
//                System.out.println("hi smarty2");
//            }
//        }
//
//      Object a = scanData.stream()
//                .filter(( s) -> s.getContractDetails().contract().symbol().equals("UUUU"))
//                .map(s -> s.getContractDetails().contract().symbol())
//                .collect(Collectors.toList());
//        System.out.println("hi handsome" + a);
        //cross moving average
//        CompletableFuture<List<Bar>> future = marketData.requestHistoricalData(contract, "1 Y", "1 min");

        //momentum indicator

        List<ScanData> result = scanLowFloatMovers();

        return true;
    }

    public List<ScanData> scanLowFloatMovers() throws ExecutionException, InterruptedException, TimeoutException {
        ScannerSubscription scannerSubscription = new ScannerSubscription();

        // Core scan definition (required)
        scannerSubscription.instrument("STK");
        scannerSubscription.locationCode("STK.US.MAJOR");
        scannerSubscription.scanCode("TOP_PERC_GAIN");  // The ranking algorithm

        // Price filters
        scannerSubscription.abovePrice(1.50);           // Only stocks above $10
        scannerSubscription.belowPrice(20.0);          // Only stocks below $500

        // Volume filters
        scannerSubscription.aboveVolume(1000);

        // Market cap filters (in millions)
//        scannerSubscription.marketCapAbove(1000.0);     // Only large-cap (>$1B market cap)

        // Additional filters
        // scannerSubscription.couponRateAbove(5.0);    // For bonds
        // scannerSubscription.moodyRatingAbove("Baa"); // Credit rating filters
        // scannerSubscription.stockTypeFilter("ALL");  // "CORP" or "ADR" or "ETF"

        //TODO: Research about filter conditions top 3  -> then also need to check whether enough liquidity
        ibkrConnection.marketScan(scannerSubscription);
        return ibkrConnection.marketScan(scannerSubscription);

    }

}
