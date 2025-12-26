package strategy;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.ScannerSubscription;
import com.ib.client.TagValue;
import ibkr.IBKRConnection;
import ibkr.model.MarketDataInput;
import ibkr.model.ScanData;
import ibkr.model.TickPriceOutput;
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
        result.stream().map(s -> s.getContractDetails().contract().symbol()).forEach(System.out::println);
        getTickPrice();
        return true;
    }

    private List<ScanData> scanLowFloatMovers() throws ExecutionException, InterruptedException, TimeoutException {
        ScannerSubscription scannerSubscription = new ScannerSubscription();

        // Core scan definition (required)
        scannerSubscription.instrument("STK");
        scannerSubscription.locationCode("STK.US.MAJOR");
        scannerSubscription.scanCode("TOP_PERC_GAIN");  // The ranking algorithm

        // Price filters
        scannerSubscription.abovePrice(1.50);
        scannerSubscription.belowPrice(20.0);


        // Volume filters
//        scannerSubscription.aboveVolume(1000);

        // Market cap filters (in millions)
//        scannerSubscription.marketCapAbove(1000.0);     // Only large-cap (>$1B market cap)

        // Additional filters
        // scannerSubscription.couponRateAbove(5.0);    // For bonds
        // scannerSubscription.moodyRatingAbove("Baa"); // Credit rating filters
        // scannerSubscription.stockTypeFilter("ALL");  // "CORP" or "ADR" or "ETF"

        // Then when calling the scanner, add the filter options:
        List<TagValue> filterOptions = new ArrayList<>();
//        filterOptions.add(new TagValue("floatSharesBelow", "100000000"));  //  Float of under 100mil shares
//        filterOptions.add(new TagValue("volumeVsAvgAbove", "2"));           // 2x relative volume


        //TODO: Research about filter conditions top 3  -> then also need to check whether enough liquidity
//        ibkrConnection.marketScan(scannerSubscription);
        return ibkrConnection.marketScan(scannerSubscription, filterOptions);

    }

    public List<TickPriceOutput> getTickPrice() throws ExecutionException, InterruptedException, TimeoutException {
//         Define an actual contract
        Contract contract = new Contract();
        contract.symbol("AAPL");
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");

        MarketDataInput marketDataInput = MarketDataInput.builder()
                .contract(contract)
                .genericTickList("100,101,104,106,165,221,232,236,258,293,294,295,318,411,460,619")
                .snapshot(false)
                .regulatorySnapshot(false)
                .tagValues(null)
                .build();


//        client.reqMktData(1, contract, "", false, false, null);

        List<TickPriceOutput> result = ibkrConnection.reqMarketData(marketDataInput);
        System.out.println("hii" + result);
        return result;
    }


}
