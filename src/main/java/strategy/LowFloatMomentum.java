package strategy;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.*;
import risk.Position;
import risk.RiskManager;
import util.Constants;
//import data.MarketDataService;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


//TODO: see do we need to fix double approximation issue because is a very rare edge case - with epsilon or using bigDecimal
// This is a long only strategy that targets low float stocks which has potential for a huge upside swing
public class LowFloatMomentum implements Strategy {
    //    private final MarketDataService marketData;
    List<ScanData> scanData;
    IBKRConnection ibkrConnection;
    Position position;
    RiskManager riskManager;

    private final double vwapSlope = 0.01;
    private final int lookBackPeriod = 10; // 10 candles back from current candle meaning first candle dont count current candle
    //TODO: We need to make sure let say the time is 10:40 now does historical API print the 10:40 candle tick by tick or print the 10:39 candle only can check with subscription

    private final double positiveTrendThreshold = 0.80;
    private final double entryThreshold = 0.01; // 1 percent
    private final double dojiThreshold = 0.05; // higher threshold percent means more candle qualify as doji
//    Contract contract = new Contract();

    private static final Logger log = LoggerFactory.getLogger(LowFloatMomentum.class);

    public LowFloatMomentum(IBKRConnection ibkrConnection, Position position, RiskManager riskManager) {
        this.ibkrConnection = ibkrConnection;
        this.position = position;
        this.riskManager = riskManager;
//        // TODO: Remove for testing purposes
//        contract.symbol("AAPL");
//        contract.secType("STK");
//        contract.exchange("SMART");
//        contract.currency("USD");
    }

    @Override
    public String getName() {
        return "LowFloatMomentumMain";
    }

    @Override
    public int getIntervalSeconds() {
        return 60;
    }

    @Override
    public LocalTime getStartTime() {
        return LocalTime.of(9, 30);
    }

    @Override
    public LocalTime getEndTime() {
        return  LocalTime.of(12, 30);
    }

    @Override
    public void run() throws ExecutionException, InterruptedException, TimeoutException {
        //TODO: Check ATR again once we have market subscription -> no subscription data is inaccurate
//        List<Bar> bars = getHistoricalPrice();
//        int atrPeriod = 14;
//        List<Double> atrSeries = ATR.calculateSeries(bars, atrPeriod);
//
//        System.out.println("\n=== ATR Analysis (Period: " + atrPeriod + ") ===");
//        System.out.println(String.format("%-25s | %-10s | %-10s | %-10s | %s",
//            "Time", "Close", "High", "Low", "ATR"));
//        System.out.println("-".repeat(75));
//
//        // ATR series starts at bar index (period), since we need 'period' TRs for first ATR
//        for (int i = 0; i < atrSeries.size(); i++) {
//            int barIndex = i + atrPeriod; // Corresponding bar index
//            Bar bar = bars.get(barIndex);
//            System.out.println(String.format("%-25s | %-10.2f | %-10.2f | %-10.2f | %.4f",
//                bar.time(), bar.close(), bar.high(), bar.low(), atrSeries.get(i)));
//        }
//
//        System.out.println("-".repeat(75));
//        System.out.println("Current ATR: " + atrSeries.get(atrSeries.size() - 1));
//        positionSizer.calculateEntry();

       /** 1. EVERY X min scanLowFloatMovers
         * 2. Get the top 5 stocks from the scanner
        * 3. Every 1 min check if all the criterias has pass
        * 4. Using positionSizer to place a bracket order
        * ---. Check holiday, check stock tradable(no halt,etc), after x:xx hours close all trades as we are intraday
         * */
//       getTickPrice();
//       ContractDetails contractDetails = ibkrConnection.reqContractDetreqContractDetailsails(contract);
//        System.out.println(contractDetails.tradingHours());
//        System.out.println(contractDetails.liquidHours());
//        System.out.println(contractDetails.timeZoneId());

//        ibkrConnection.reqAllOpenOrder();
//        List<PositionOutput> positions = ibkrConnection.reqPositions();
//        System.out.println("hi1 " + positions);
//
//        //TODO: we make a successful order here then we reqPositions again see if it works properly
//        //ibkrConnection.placeOrder();
//        List<PositionOutput> positions2 = ibkrConnection.reqPositions();
//        System.out.println("hi2 " + positions2);

//        List<OrderOutput> orderOutputs =  ibkrConnection.reqAllOpenOrder();




        List<ScanData> filterStocks = scanLowFloatMovers();

        for (ScanData filterStock : filterStocks) {
            if (filterStock.getContractDetails() == null || filterStock.getContractDetails().contract() == null) {
                log.warn("Skipping scan result with null contract details");
                continue;
            }

            Contract contract = filterStock.getContractDetails().contract();
            String symbol = contract.symbol();
            log.debug("[{}] Evaluating stock from scanner (rank={})", symbol, filterStock.getRank());

            List<Bar> historicalPrices = getHistoricalPrice(contract);
            if (historicalPrices.isEmpty()) {
                log.warn("[{}] No historical price data available - skipping", symbol);
                continue;
            }
            log.debug("[{}] Retrieved {} historical bars", symbol, historicalPrices.size());

            // This api call reqContractDetails is necessary as ScanData Contract Details does not return trading hours
            ContractDetails contractDetails = ibkrConnection.reqContractDetails(contract);

            // 1. Safety checks first
            List<TickPriceOutput> tickPrices = getTickPrice(contract);
            boolean isStockTradeable = riskManager.isStockTradeable(tickPrices, contractDetails.tradingHours());

            List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();
            boolean hasOrder = riskManager.hasOrder(orders, symbol);

            List<PositionOutput> positions = ibkrConnection.reqPositions();
            boolean hasPosition = riskManager.hasPosition(positions, symbol);

            if (!isStockTradeable) {
                log.info("[{}] SKIP: Stock not tradeable (halted or outside hours)", symbol);
                continue;
            }
            if (hasOrder) {
                log.info("[{}] SKIP: Already has open order", symbol);
                continue;
            }
            if (hasPosition) {
                log.info("[{}] SKIP: Already has position", symbol);
                continue;
            }

            // 2. Business Logic - Strategy conditions
            boolean trendValid = isTrend(historicalPrices);
            boolean vwapExtensionValid = isVwapExtension(historicalPrices);

            if (!trendValid) {
                log.info("[{}] SKIP: Trend condition not met", symbol);
                continue;
            }
            if (!vwapExtensionValid) {
                log.info("[{}] SKIP: VWAP extension condition not met", symbol);
                continue;
            }

            // 3. Entry condition
            Bar currentBar = historicalPrices.getLast();
            if (!isEntry(currentBar)) {
                log.info("[{}] SKIP: Entry condition not met (close={}, vwap={})",
                        symbol, currentBar.close(), currentBar.wap());
                continue;
            }

            // 4. All conditions passed - place order
            log.info("[{}] ALL CONDITIONS MET - Placing bracket order. Price={}, VWAP={}",
                    symbol, currentBar.close(), currentBar.wap());
            position.calculateEntryLowFloatMomentum(contract, currentBar, historicalPrices, "Low Float Momentum Main");
        }
    }

    private List<ScanData> scanLowFloatMovers() throws ExecutionException, InterruptedException, TimeoutException {
        ScannerSubscription scannerSubscription = new ScannerSubscription();

        // Core scan definition (required)
        scannerSubscription.instrument("STK");
        scannerSubscription.locationCode("STK.US.MAJOR");
        scannerSubscription.scanCode("TOP_PERC_GAIN");  // The ranking algorithm
        scannerSubscription.numberOfRows(6);  // Only top 6 gainers

        // Price filters
        scannerSubscription.abovePrice(1.50);
        scannerSubscription.belowPrice(20.0);

        List<TagValue> filterOptions = new ArrayList<>();
//        filterOptions.add(new TagValue("floatSharesBelow", "20000000"));  //  Float of under 20mil shares
//        filterOptions.add(new TagValue("volumeVsAvgAbove", "2"));           // 2x relative volume
//        filterOptions.add(new TagValue("volumeAbove", "100000")); // 100k volume count from RTH only
        // See if this condition is enough to check for liquidity issues

        // Volume filters
//        scannerSubscription.aboveVolume(1000);

        // Market cap filters (in millions)
//        scannerSubscription.marketCapAbove(1000.0);     // Only large-cap (>$1B market cap)

        // Additional filters
        // scannerSubscription.couponRateAbove(5.0);    // For bonds
        // scannerSubscription.moodyRatingAbove("Baa"); // Credit rating filters
        // scannerSubscription.stockTypeFilter("ALL");  // "CORP" or "ADR" or "ETF"

        return ibkrConnection.marketScan(scannerSubscription, filterOptions);

    }

    public List<TickPriceOutput> getTickPrice(Contract contract) throws ExecutionException, InterruptedException, TimeoutException {
        MarketDataInput marketDataInput = MarketDataInput.builder()
                .contract(contract)
                .genericTickList("")
//                .genericTickList("100,101,104,106,165,221,232,236,258,293,294,295,318,411,460,619")
                .snapshot(true)
                .regulatorySnapshot(false)
                .tagValues(null)
                .build();

        List<TickPriceOutput> result = ibkrConnection.reqMarketData(marketDataInput);
        return result;
    }

    private List<Bar> getHistoricalPrice(Contract contract) throws ExecutionException, InterruptedException, TimeoutException {
        HistoricalDataInput historicalDataInput = HistoricalDataInput.builder()
                .contract(contract)
                .endDateTime("")
                .durationStr("1 D")
                .barSize(Types.BarSize._1_min)
                .whatToShow(Types.WhatToShow.TRADES)
                .useRth(HistoricalDataInput.UseRth.ALL_HOURS)
                .formatData(HistoricalDataInput.FormatData.HUMAN_READABLE)
                .keepUpToDate(false)
                .chartOptions(null)
                .build();

        List<Bar> historicalData = ibkrConnection.reqHistoricalData(historicalDataInput);

        //Clean Code way of doing npe checks - this way our callers dont need defensive check everywhere
        return historicalData != null ? historicalData : Collections.emptyList();
    }


    // Check trend is correct
    public boolean isTrend(List<Bar> historicalData) {
        /*
            1. Price > VWAP (80% of the bars)
            2. VWAP Slope VWAP_Curr - VWAP_10_Candles_Back >= 1.5%
        * */

        if(historicalData.size() <= lookBackPeriod) {
            return false;
        }

        int lookBackPeriodIndex = historicalData.size() - lookBackPeriod - 1;

        double currCandle = historicalData.getLast().wap().value().doubleValue();
        double lookBackCandle  =  historicalData.get(lookBackPeriodIndex).wap().value().doubleValue();

        // Division by zero check - only affect denominator
        if (lookBackCandle <= 0 ){
            return false;
        }

        if (((currCandle - lookBackCandle) / lookBackCandle) < vwapSlope) {
            return false;
        }

        int barsAboveVwap = 0;

        for (int i = historicalData.size() - 1; i >= historicalData.size() - lookBackPeriod; i--) {
           if (historicalData.get(i).close() > historicalData.get(i).wap().value().doubleValue()) {
               barsAboveVwap++;
           }
        }

        double priceAboveVwapPerc = (double) barsAboveVwap / lookBackPeriod;

        if(priceAboveVwapPerc < positiveTrendThreshold) {
            return false;
        }

//        List test =  historicalData.stream()
//                .skip(Math.max(0, historicalData.size() - lookBackPeriod))
//                .filter(x -> x.close() > x.wap().value().doubleValue())
//                .peek(x -> System.out.println(x.wap()))
//                .toList();
//        System.out.println(test);
        return true;

    }

    // Check to ensure that the price has move enough from VWAP - think of it as a rubberband
    private boolean isVwapExtension(List<Bar> historicalData) {
        Bar maxCloseBar = historicalData.stream()
                .skip(Math.max(0, historicalData.size() - lookBackPeriod))
                .max(Comparator.comparingDouble(Bar::close))
                .orElse(null);

        if (maxCloseBar == null) {
            return false;
        }

        // The maxVwap is based on the highest closing bar
        double maxVwap = maxCloseBar.wap().value().doubleValue();
        double maxClose = maxCloseBar.close();

        // Division by zero check
        if (maxVwap <= 0 ){
            return false;
        }

        double extensionPerc = (maxClose - maxVwap) / maxVwap * 100;

        //it is vwap extended if is within 0.5% and 2.5%
        if (extensionPerc < 0.5 || extensionPerc > 2.5 ){
            return false;
        }

        return true;

    }

    // Check entry  condition
    private boolean isEntry(Bar currentBar) {
        /** 1st condition: price above vwap and price not too far above VWAP (within entry threshold)
            2nd condition: Check if is a bullish candle
                1. Close > Open
                2. Not a Doji candle
         **/

        double currentVwap = currentBar.wap().value().doubleValue();

        if (currentBar.close() <= currentVwap ||
            currentBar.close() > currentVwap * (1 + entryThreshold)) {
            return false;
        }

        if(currentBar.close() <= currentBar.open()){
            return false;
        }

        if(dojiCandle(currentBar)) {
            return false;
        }

        return true;
    }

    // A doji candle has a very small body (open ~ close) with long wicks
    private boolean dojiCandle (Bar bar) {
        double bodySize = Math.abs(bar.open() - bar.close());
        double totalRange = bar.high() - bar.low();

        return bodySize <= (totalRange * dojiThreshold);
    }

    private void closeAllIntradayPositionsAndOrders (LocalTime cutOffTime, List<PositionOutput> positions) {
        if(Constants.timeNow().isAfter(cutOffTime)) {
            position.closeAllPositions(positions);
            ibkrConnection.closeAllOrders();
        }
    }

    public void onEndOfDay() {
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            position.closeAllPositions(positions);
            ibkrConnection.closeAllOrders();
            System.out.println("[" + getName() + "] Closed all intraday positions and orders");
        } catch (Exception e) {
            System.err.println("[" + getName() + "] Failed to close positions: " + e.getMessage());
        }
    }

}
