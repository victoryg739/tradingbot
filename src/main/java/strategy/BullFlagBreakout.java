package strategy;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.*;
import indicators.ATR;
import risk.Position;
import risk.RiskManager;
import util.Constants;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bull Flag Breakout Strategy (Warrior Trading style)
 *
 * After a gap-up morning spike, low float stocks consolidate in a tight range
 * (the "flag") before continuing higher. We detect that consolidation, then
 * enter when the current bar is at/breaking the flag high.
 *
 * Entry conditions (in order):
 *   1. isTrend()              — VWAP slope ≥ 0.5%, ≥ 70% of last 10 bars above VWAP
 *   2. isFlagConsolidation()  — last 6 bars are tight (≤ 1.5×ATR), ≥ 50% above VWAP
 *   3. isFlagBreakout()       — current bar closes ≥ 99.5% of flag high, bullish, above VWAP, not doji
 *   4. Risk pre-check         — stop width (flagHigh+3ticks − flagLow) / flagHigh ≤ 3%
 *
 * Entry:  flagHigh + 3 ticks (BUY LIMIT)
 * Stop:   flagLow
 * Target: 2.0× R:R
 * Window: 9:30–11:30 AM ET
 */
public class BullFlagBreakout implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(BullFlagBreakout.class);
    private static final Logger orderLog = LoggerFactory.getLogger("ORDER_AUDIT");

    private final IBKRConnection ibkrConnection;
    private final Position position;
    private final RiskManager riskManager;

    // Trend parameters
    private final double vwapSlope = 0.005;             // VWAP must rise ≥ 0.5% over lookback
    private final int lookBackPeriod = 10;              // bars used for trend check
    private final double positiveTrendThreshold = 0.70; // ≥ 70% of lookback bars must close above VWAP

    // Flag parameters
    private final int flagLookback = 6;                 // bars that form the flag (excl. current)
    private final double flagAtrMultiple = 1.5;         // flag range must be ≤ 1.5× ATR(10)
    private final double flagVwapMinRatio = 0.50;       // ≥ 50% of flag bars must close above VWAP
    private final double breakoutTolerance = 0.995;     // current bar close ≥ 99.5% of flag high

    // Risk
    private final double maxRiskPercent = 0.03;         // skip if stop width > 3%
    private final double dojiThreshold = 0.05;          // body ≤ 5% of total range → doji
    private final double riskRewardMultiple = 2.0;      // take profit at 2× risk

    public BullFlagBreakout(IBKRConnection ibkrConnection, Position position, RiskManager riskManager) {
        this.ibkrConnection = ibkrConnection;
        this.position = position;
        this.riskManager = riskManager;
    }

    @Override
    public String getName() {
        return "Bull Flag Breakout";
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
        return LocalTime.of(11, 30);
    }

    @Override
    public void run() throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("[BullFlagBreakout] Starting strategy cycle");

        List<ScanData> filteredStocks = scanLowFloatMovers();
        log.info("[BullFlagBreakout] Scanner returned {} candidates", filteredStocks.size());

        List<OrderOutput> orders = ibkrConnection.reqAllOpenOrder();
        List<PositionOutput> positions = ibkrConnection.reqPositions();

        for (ScanData scanResult : filteredStocks) {
            if (scanResult.getContractDetails() == null || scanResult.getContractDetails().contract() == null) {
                log.warn("[BullFlagBreakout] Skipping scan result with null contract details");
                continue;
            }

            Contract contract = scanResult.getContractDetails().contract();
            String symbol = contract.symbol();
            log.debug("[{}] Evaluating candidate (rank={})", symbol, scanResult.getRank());

            // --- Historical data ---
            List<Bar> historicalPrices = getHistoricalPrice(contract);
            if (historicalPrices.isEmpty()) {
                log.warn("[{}] SKIP: No historical price data available", symbol);
                continue;
            }
            log.debug("[{}] Retrieved {} historical bars", symbol, historicalPrices.size());

            // --- Safety checks ---
            ContractDetails contractDetails = ibkrConnection.reqContractDetails(contract);
            List<TickPriceOutput> tickPrices = getTickPrice(contract);
            boolean isStockTradeable = riskManager.isStockTradeable(tickPrices, contractDetails.tradingHours());
            boolean hasOrder = riskManager.hasOrder(orders, symbol);
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

            // --- Condition 1: Trend ---
            boolean trendValid = isTrend(historicalPrices);
            if (!trendValid) {
                log.info("[{}] SKIP: Trend condition not met (need VWAP slope ≥{}% and ≥{}% bars above VWAP)",
                        symbol, vwapSlope * 100, positiveTrendThreshold * 100);
                continue;
            }
            log.debug("[{}] PASS: Trend condition met", symbol);

            // --- Condition 2: Flag consolidation ---
            boolean flagValid = isFlagConsolidation(historicalPrices);
            if (!flagValid) {
                log.info("[{}] SKIP: Flag consolidation not met (last {} bars must be tight ≤{}×ATR, ≥{}% above VWAP)",
                        symbol, flagLookback, flagAtrMultiple, flagVwapMinRatio * 100);
                continue;
            }
            log.debug("[{}] PASS: Flag consolidation confirmed — flagHigh={}, flagLow={}",
                    symbol, getFlagHigh(historicalPrices), getFlagLow(historicalPrices));

            // --- Condition 3: Breakout on current bar ---
            Bar currentBar = historicalPrices.getLast();
            boolean breakoutValid = isFlagBreakout(currentBar, historicalPrices);
            if (!breakoutValid) {
                double flagHigh = getFlagHigh(historicalPrices);
                double currentVwap = currentBar.wap().value().doubleValue();
                log.info("[{}] SKIP: Breakout condition not met (close={}, flagHigh={}, vwap={}, open={})",
                        symbol, currentBar.close(), flagHigh, currentVwap, currentBar.open());
                continue;
            }
            log.debug("[{}] PASS: Breakout bar confirmed — close={}, open={}, vwap={}",
                    symbol, currentBar.close(), currentBar.open(), currentBar.wap());

            // --- Condition 4: Risk pre-check ---
            double flagHigh = getFlagHigh(historicalPrices);
            double flagLow  = getFlagLow(historicalPrices);
            double entryCandidate = flagHigh + Constants.STANDARD_TICK_SIZE * 3;
            double stopWidth = (entryCandidate - flagLow) / entryCandidate;

            if (stopWidth > maxRiskPercent) {
                log.warn("[{}] SKIP: Risk too wide ({:.2f}% > {:.2f}%) — flagHigh={}, flagLow={}, entry={}",
                        symbol,
                        String.format("%.2f", stopWidth * 100),
                        String.format("%.2f", maxRiskPercent * 100),
                        flagHigh, flagLow, entryCandidate);
                continue;
            }
            log.debug("[{}] PASS: Risk check OK ({:.2f}% stop width)", symbol,
                    String.format("%.2f", stopWidth * 100));

            // --- All conditions met — place order ---
            log.info("[{}] *** ALL CONDITIONS MET *** Placing Bull Flag bracket order. " +
                            "flagHigh={}, flagLow={}, entry={}, stopWidth={}%, vwap={}",
                    symbol, flagHigh, flagLow,
                    String.format("%.2f", entryCandidate),
                    String.format("%.2f", stopWidth * 100),
                    currentBar.wap());

            position.calculateEntryBullFlag(contract, currentBar, flagHigh, flagLow, historicalPrices, getName());
        }

        log.debug("[BullFlagBreakout] Strategy cycle complete");
    }

    // -------------------------------------------------------------------------
    // Scanner
    // -------------------------------------------------------------------------

    private List<ScanData> scanLowFloatMovers() throws ExecutionException, InterruptedException, TimeoutException {
        ScannerSubscription scannerSubscription = new ScannerSubscription();
        scannerSubscription.instrument("STK");
        scannerSubscription.locationCode("STK.US.MAJOR");
        scannerSubscription.scanCode("TOP_PERC_GAIN");
        scannerSubscription.numberOfRows(6);

        // Price range typical for low float momentum plays
        scannerSubscription.abovePrice(1.50);
        scannerSubscription.belowPrice(20.0);

        List<TagValue> filterOptions = new ArrayList<>();
        filterOptions.add(new TagValue("floatSharesBelow", "20000000")); // true low float: < 20M shares
        filterOptions.add(new TagValue("volumeVsAvgAbove", "3"));        // 3× relative volume

        log.debug("[BullFlagBreakout] Scanning with floatSharesBelow=20M, volumeVsAvgAbove=3");
        return ibkrConnection.marketScan(scannerSubscription, filterOptions);
    }

    // -------------------------------------------------------------------------
    // Market data helpers
    // -------------------------------------------------------------------------

    public List<TickPriceOutput> getTickPrice(Contract contract) throws ExecutionException, InterruptedException, TimeoutException {
        MarketDataInput marketDataInput = MarketDataInput.builder()
                .contract(contract)
                .genericTickList("")
                .snapshot(true)
                .regulatorySnapshot(false)
                .tagValues(null)
                .build();
        return ibkrConnection.reqMarketData(marketDataInput);
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
        try {
            List<Bar> historicalData = ibkrConnection.reqHistoricalData(historicalDataInput);
            return historicalData != null ? historicalData : Collections.emptyList();
        } catch (TimeoutException e) {
            log.warn("[{}] Historical data request timed out - skipping", contract.symbol());
            return Collections.emptyList();
        } catch (ExecutionException | InterruptedException e) {
            log.warn("[{}] Historical data request failed: {}", contract.symbol(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Strategy conditions
    // -------------------------------------------------------------------------

    /**
     * Condition 1 — Uptrend: VWAP rising ≥ 0.5% over last 10 bars,
     * and ≥ 70% of those bars close above VWAP.
     */
    boolean isTrend(List<Bar> historicalData) {
        if (historicalData.size() <= lookBackPeriod) {
            log.debug("[isTrend] Insufficient bars: {} ≤ {}", historicalData.size(), lookBackPeriod);
            return false;
        }

        int lookBackIndex = historicalData.size() - lookBackPeriod - 1;
        double currVwap = historicalData.getLast().wap().value().doubleValue();
        double pastVwap = historicalData.get(lookBackIndex).wap().value().doubleValue();

        if (pastVwap <= 0) {
            log.debug("[isTrend] Past VWAP is zero — skipping");
            return false;
        }

        double slope = (currVwap - pastVwap) / pastVwap;
        if (slope < vwapSlope) {
            log.debug("[isTrend] VWAP slope too low: {:.3f}% < {:.3f}%",
                    String.format("%.3f", slope * 100), String.format("%.3f", vwapSlope * 100));
            return false;
        }

        int barsAboveVwap = 0;
        for (int i = historicalData.size() - 1; i >= historicalData.size() - lookBackPeriod; i--) {
            Bar b = historicalData.get(i);
            if (b.close() > b.wap().value().doubleValue()) barsAboveVwap++;
        }

        double ratio = (double) barsAboveVwap / lookBackPeriod;
        if (ratio < positiveTrendThreshold) {
            log.debug("[isTrend] Too few bars above VWAP: {:.1f}% < {:.1f}%",
                    String.format("%.1f", ratio * 100), String.format("%.1f", positiveTrendThreshold * 100));
            return false;
        }

        log.debug("[isTrend] OK — slope={:.3f}%, barsAboveVwap={}/{}",
                String.format("%.3f", slope * 100), barsAboveVwap, lookBackPeriod);
        return true;
    }

    /**
     * Condition 2 — Flag consolidation: the last {@code flagLookback} bars
     * (excluding current) form a tight range ≤ 1.5×ATR(10) with ≥ 50% closing
     * above VWAP.
     */
    boolean isFlagConsolidation(List<Bar> bars) {
        int minRequired = lookBackPeriod + flagLookback + 1;
        if (bars.size() < minRequired) {
            log.debug("[isFlagConsolidation] Insufficient bars: {} < {}", bars.size(), minRequired);
            return false;
        }

        double flagHigh = getFlagHigh(bars);
        double flagLow  = getFlagLow(bars);
        if (flagLow <= 0) {
            log.debug("[isFlagConsolidation] flagLow is zero");
            return false;
        }

        double atr = ATR.calculate(bars, 10);
        double flagRange = flagHigh - flagLow;
        if (flagRange > flagAtrMultiple * atr) {
            log.debug("[isFlagConsolidation] Flag too wide: range={:.4f} > {}×ATR={:.4f}",
                    String.format("%.4f", flagRange),
                    flagAtrMultiple,
                    String.format("%.4f", flagAtrMultiple * atr));
            return false;
        }

        List<Bar> flagBars = bars.subList(bars.size() - flagLookback - 1, bars.size() - 1);
        long barsAboveVwap = flagBars.stream()
                .filter(b -> b.close() > b.wap().value().doubleValue())
                .count();
        double vwapRatio = (double) barsAboveVwap / flagLookback;
        if (vwapRatio < flagVwapMinRatio) {
            log.debug("[isFlagConsolidation] Not enough flag bars above VWAP: {:.1f}% < {:.1f}%",
                    String.format("%.1f", vwapRatio * 100), String.format("%.1f", flagVwapMinRatio * 100));
            return false;
        }

        log.debug("[isFlagConsolidation] OK — flagHigh={}, flagLow={}, range={:.4f}, ATR={:.4f}, vwapRatio={:.1f}%",
                String.format("%.2f", flagHigh), String.format("%.2f", flagLow),
                String.format("%.4f", flagRange), String.format("%.4f", atr),
                String.format("%.1f", vwapRatio * 100));
        return true;
    }

    /**
     * Condition 3 — Breakout bar: current bar closes at/above flag high (within
     * 0.5% tolerance), is bullish (close > open), above VWAP, and not a doji.
     */
    boolean isFlagBreakout(Bar currentBar, List<Bar> bars) {
        double flagHigh = getFlagHigh(bars);
        double currentVwap = currentBar.wap().value().doubleValue();

        if (currentVwap <= 0) {
            log.debug("[isFlagBreakout] VWAP is zero on current bar");
            return false;
        }

        // Must close at/near flag high
        if (currentBar.close() < flagHigh * breakoutTolerance) {
            log.debug("[isFlagBreakout] Close {:.2f} below flagHigh×tolerance {:.2f}",
                    String.format("%.2f", currentBar.close()),
                    String.format("%.2f", flagHigh * breakoutTolerance));
            return false;
        }

        // Must be a bullish bar
        if (currentBar.close() <= currentBar.open()) {
            log.debug("[isFlagBreakout] Not bullish: close={}, open={}", currentBar.close(), currentBar.open());
            return false;
        }

        // Must close above VWAP
        if (currentBar.close() <= currentVwap) {
            log.debug("[isFlagBreakout] Close {:.2f} not above VWAP {:.2f}",
                    String.format("%.2f", currentBar.close()), String.format("%.2f", currentVwap));
            return false;
        }

        // Must not be a doji
        if (dojiCandle(currentBar)) {
            log.debug("[isFlagBreakout] Doji candle detected — skipping");
            return false;
        }

        log.debug("[isFlagBreakout] OK — close={}, flagHigh={}, vwap={}, open={}",
                currentBar.close(), flagHigh, currentVwap, currentBar.open());
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Highest high of the last {@code flagLookback} bars (excluding current). */
    private double getFlagHigh(List<Bar> bars) {
        List<Bar> flagBars = bars.subList(bars.size() - flagLookback - 1, bars.size() - 1);
        return flagBars.stream().mapToDouble(Bar::high).max().orElse(0.0);
    }

    /** Lowest low of the last {@code flagLookback} bars (excluding current). */
    private double getFlagLow(List<Bar> bars) {
        List<Bar> flagBars = bars.subList(bars.size() - flagLookback - 1, bars.size() - 1);
        return flagBars.stream().mapToDouble(Bar::low).min().orElse(0.0);
    }

    /** Returns true when the bar body is ≤ dojiThreshold × total range. */
    private boolean dojiCandle(Bar bar) {
        double bodySize = Math.abs(bar.open() - bar.close());
        double totalRange = bar.high() - bar.low();
        return bodySize <= (totalRange * dojiThreshold);
    }

    @Override
    public void onEndOfDay() {
        try {
            List<PositionOutput> positions = ibkrConnection.reqPositions();
            position.closeAllPositions(positions);
            ibkrConnection.closeAllOrders();
            log.info("[BullFlagBreakout] Closed all intraday positions and orders at end of day");
        } catch (Exception e) {
            log.error("[BullFlagBreakout] Failed to close positions at end of day", e);
        }
    }
}
