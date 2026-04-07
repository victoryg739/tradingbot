package strategy;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.*;
import risk.Position;
import risk.RiskManager;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bull Flag Breakout Strategy (Ross Cameron / Warrior Trading style)
 *
 * Models the three structural phases explicitly via a state machine:
 *   Pole  — a sharp, high-volume green candle (≥1.3× avg body, ≥1.5× avg vol)
 *   Flag  — 1–3 low-volume red candles pulling back ≤50% of pole range
 *   Break — green candle closing above the prior red's high AND pushing high above HOD
 *           with volume ≥1.2× avg
 *
 * All averages are computed over a rolling 20-bar window for self-calibration.
 * Window: 9:30–11:30 AM ET
 */
public class BullFlagBreakout implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(BullFlagBreakout.class);
    private static final Logger orderLog = LoggerFactory.getLogger("ORDER_AUDIT");

    private final IBKRConnection ibkrConnection;
    private final Position position;
    private final RiskManager riskManager;

    private static final int    ROLLING_WINDOW             = 10;
    private static final int    ROLLING_MIN_BARS           = 5;    // minimum bars before averages are trusted
    private static final double POLE_BODY_MULTIPLIER       = 1.3;  // pole bar body ≥ 1.3× avgBody
    private static final double POLE_VOLUME_MULTIPLIER     = 1.5;  // pole bar vol  ≥ 1.5× avgVol
    private static final double FLAG_DEPTH_RATIO           = 0.65; // pullback ≤ 65% of pole range
    private static final double FLAG_VOLUME_MULTIPLIER     = 1.3;  // flag bar vol  < 1.3× baselineAvgVol (orderly pullback)
    private static final double BREAKOUT_VOLUME_MULTIPLIER = 1.0;  // breakout vol ≥ 1.0× avgVol (at or above baseline)
    private static final int    FLAG_MAX_BARS              = 8;    // abandon flag after 8 bars with no breakout
    private static final int    FLAG_MAX_RED_BARS          = 4;    // max red candles in flag before reset (was hard-coded 3)
    private static final int    POLE_MAX_NON_QUAL          = 2;    // max non-qualifying greens in POLE_FORMING before reset

    public BullFlagBreakout(IBKRConnection ibkrConnection, Position position, RiskManager riskManager) {
        this.ibkrConnection = ibkrConnection;
        this.position = position;
        this.riskManager = riskManager;
    }

    // -------------------------------------------------------------------------
    // State machine types
    // -------------------------------------------------------------------------

    private enum State { IDLE, POLE_FORMING, FLAG_FORMING }

    private record FlagSetup(Bar breakoutBar, double poleTopHigh, double flagLow) {}

    // -------------------------------------------------------------------------
    // Strategy interface
    // -------------------------------------------------------------------------

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

            // --- State-machine setup detection ---
            FlagSetup setup = findSetup(historicalPrices, symbol);
            if (setup == null) {
                log.info("[{}] SKIP: No bull flag setup found in {} bars", symbol, historicalPrices.size());
                continue;
            }

            // --- Freshness check: breakout must be the current bar ---
            String lastBarTime = historicalPrices.getLast().time();
            if (!setup.breakoutBar().time().equals(lastBarTime)) {
                log.info("[{}] SKIP: Stale setup (breakout was at {}, current bar is {})",
                        symbol, setup.breakoutBar().time(), lastBarTime);
                continue;
            }

            log.info("[{}] *** SETUP CONFIRMED *** poleTopHigh={}, flagLow={}, breakoutClose={}, breakoutTime={}",
                     symbol, setup.poleTopHigh(), setup.flagLow(),
                     setup.breakoutBar().close(), setup.breakoutBar().time());

            position.calculateEntryBullFlag(contract, setup.breakoutBar(),
                    setup.poleTopHigh(), setup.flagLow(), historicalPrices, getName());
        }

        log.debug("[BullFlagBreakout] Strategy cycle complete");
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    /**
     * Runs a Pole → Flag → Breakout state machine over {@code bars} in
     * chronological order, skipping bars before 9:30 AM.
     * Returns the <em>last</em> valid {@link FlagSetup} found, or {@code null}.
     */
    FlagSetup findSetup(List<Bar> bars, String symbol) {
        // Pre-filter to market-hours bars only so rolling averages are never
        // polluted by pre-market candles (tiny bodies, near-zero volume).
        List<Bar> marketBars = new ArrayList<>();
        for (Bar b : bars) {
            if (!parseBarTime(b.time()).isBefore(LocalTime.of(9, 30))) {
                marketBars.add(b);
            }
        }

        State state = State.IDLE;
        List<Bar> poleCandles = new ArrayList<>();
        List<Bar> flagCandles = new ArrayList<>();
        double poleTopHigh      = 0.0;
        double poleRange        = 0.0;
        double flagLow          = Double.MAX_VALUE;
        double priorRedHigh     = 0.0;
        int    flagBarsTotal    = 0;
        int    poleNonQualCount = 0;  // consecutive non-qualifying greens in POLE_FORMING
        // Frozen pre-pole baseline — used for all post-detection checks so that
        // the pole's own large body/volume doesn't inflate the rolling average.
        double baselineAvgBody  = 0.0;
        double baselineAvgVol   = 0.0;
        FlagSetup lastSetup     = null;

        for (int i = 0; i < marketBars.size(); i++) {
            Bar bar = marketBars.get(i);

            double avgBody = avgBodySize(marketBars, i);
            double avgVol  = avgVolume(marketBars, i);

            if (avgBody == 0.0 || avgVol == 0.0) {
                log.debug("[{}] SKIP bar {}: insufficient history for rolling averages", symbol, bar.time());
                continue;
            }

            double body = Math.abs(bar.close() - bar.open());
            double vol  = bar.volume().value().doubleValue();

            switch (state) {
                case IDLE -> {
                    if (isGreen(bar)
                            && body >= POLE_BODY_MULTIPLIER * avgBody
                            && vol  >= POLE_VOLUME_MULTIPLIER * avgVol) {
                        // Snapshot clean baseline before pole bars enter the rolling window
                        baselineAvgBody = avgBody;
                        baselineAvgVol  = avgVol;
                        poleCandles = new ArrayList<>();
                        poleCandles.add(bar);
                        state = State.POLE_FORMING;
                        log.info("[{}] PASS: Pole started at {} (body={}, baselineAvgBody={}, vol={}, baselineAvgVol={})",
                                symbol, bar.time(),
                                String.format("%.4f", body), String.format("%.4f", baselineAvgBody),
                                String.format("%.0f", vol), String.format("%.0f", baselineAvgVol));
                    } else {
                        log.debug("[{}] IDLE bar {} does not qualify as pole start", symbol, bar.time());
                    }
                }

                case POLE_FORMING -> {
                    if (isGreen(bar)
                            && body >= POLE_BODY_MULTIPLIER * baselineAvgBody
                            && vol  >= POLE_VOLUME_MULTIPLIER * baselineAvgVol) {
                        poleNonQualCount = 0;
                        poleCandles.add(bar);
                        if (poleCandles.size() > 4) {
                            log.info("[{}] SKIP: Parabolic pole ({} big green candles) — resetting IDLE",
                                    symbol, poleCandles.size());
                            state = State.IDLE;
                            poleNonQualCount = 0;
                        }
                    } else if (isRed(bar)) {
                        poleNonQualCount = 0;
                        // Pole complete — transition to flag
                        poleTopHigh  = poleCandles.stream().mapToDouble(Bar::high).max().orElse(0.0);
                        double poleBottomLow = poleCandles.stream().mapToDouble(Bar::low).min().orElse(poleCandles.getFirst().low());
                        poleRange    = poleTopHigh - poleBottomLow;

                        if (poleRange <= 0) {
                            log.warn("[{}] SKIP: Pole range is zero — resetting IDLE", symbol);
                            state = State.IDLE;
                            break;
                        }

                        // Validate first flag bar
                        double depth = poleTopHigh - bar.low();
                        if (depth <= FLAG_DEPTH_RATIO * poleRange && vol < FLAG_VOLUME_MULTIPLIER * baselineAvgVol) {
                            flagCandles  = new ArrayList<>();
                            flagCandles.add(bar);
                            flagLow      = bar.low();
                            priorRedHigh = bar.high();
                            flagBarsTotal = 1;
                            state        = State.FLAG_FORMING;
                            log.info("[{}] PASS: Pole complete (poleTopHigh={}, poleRange={}), flag started at {}",
                                    symbol,
                                    String.format("%.4f", poleTopHigh),
                                    String.format("%.4f", poleRange),
                                    bar.time());
                        } else {
                            log.info("[{}] SKIP: First flag bar failed validation " +
                                     "(depth={}, maxDepth={}, vol={}, baselineAvgVol={}) — resetting IDLE",
                                    symbol,
                                    String.format("%.4f", depth),
                                    String.format("%.4f", FLAG_DEPTH_RATIO * poleRange),
                                    String.format("%.0f", vol),
                                    String.format("%.0f", baselineAvgVol));
                            state = State.IDLE;
                        }
                    } else {
                        // Non-qualifying green — allow up to POLE_MAX_NON_QUAL before resetting
                        poleNonQualCount++;
                        if (poleNonQualCount > POLE_MAX_NON_QUAL) {
                            log.info("[{}] SKIP: Too many non-qualifying greens in POLE_FORMING ({}) — resetting IDLE",
                                    symbol, poleNonQualCount);
                            state = State.IDLE;
                            poleNonQualCount = 0;
                        } else {
                            log.debug("[{}] Non-qualifying green in POLE_FORMING (count={}/{}) — staying in POLE_FORMING",
                                    symbol, poleNonQualCount, POLE_MAX_NON_QUAL);
                        }
                    }
                }

                case FLAG_FORMING -> {
                    flagBarsTotal++;
                    if (flagBarsTotal > FLAG_MAX_BARS) {
                        log.info("[{}] SKIP: Flag expired ({} bars with no breakout) — resetting IDLE",
                                symbol, flagBarsTotal - 1);
                        state = State.IDLE;
                        // Re-evaluate this bar as a potential new pole start rather than discarding it
                        if (isGreen(bar) && body >= POLE_BODY_MULTIPLIER * avgBody && vol >= POLE_VOLUME_MULTIPLIER * avgVol) {
                            log.info("[{}] PASS: Flag-expiry bar qualifies as new pole start at {}", symbol, bar.time());
                            baselineAvgBody = avgBody;
                            baselineAvgVol  = avgVol;
                            poleCandles = new ArrayList<>();
                            poleCandles.add(bar);
                            state = State.POLE_FORMING;
                        }
                        break;
                    }
                    if (isRed(bar)) {
                        if (flagCandles.size() >= FLAG_MAX_RED_BARS) {
                            log.info("[{}] SKIP: Too many red flag candles ({}) — resetting IDLE",
                                    symbol, flagCandles.size());
                            state = State.IDLE;
                            break;
                        }
                        double depth = poleTopHigh - bar.low();
                        if (depth > FLAG_DEPTH_RATIO * poleRange) {
                            log.info("[{}] SKIP: Flag pulled back too deep " +
                                     "(depth={} > maxDepth={}) — resetting IDLE",
                                    symbol,
                                    String.format("%.4f", depth),
                                    String.format("%.4f", FLAG_DEPTH_RATIO * poleRange));
                            state = State.IDLE;
                            break;
                        }
                        if (vol >= FLAG_VOLUME_MULTIPLIER * baselineAvgVol) {
                            log.info("[{}] SKIP: Heavy selling in flag (vol={} >= {}× baselineAvgVol={}) — resetting IDLE",
                                    symbol,
                                    String.format("%.0f", vol),
                                    FLAG_VOLUME_MULTIPLIER,
                                    String.format("%.0f", baselineAvgVol));
                            state = State.IDLE;
                            break;
                        }
                        flagCandles.add(bar);
                        flagLow      = Math.min(flagLow, bar.low());
                        priorRedHigh = bar.high();
                        log.info("[{}] PASS: Flag candle added (total={}, flagLow={}, priorRedHigh={})",
                                symbol, flagCandles.size(),
                                String.format("%.4f", flagLow),
                                String.format("%.4f", priorRedHigh));

                    } else {
                        // Green bar — potential breakout
                        if (flagCandles.isEmpty()) {
                            log.info("[{}] SKIP: Not enough red flag candles (0 < 1) — resetting IDLE", symbol);
                            state = State.IDLE;
                            break;
                        }
                        boolean breaksPriorRed  = bar.close() > priorRedHigh;
                        boolean breaksHOD       = bar.high() >= poleTopHigh * 0.99;
                        boolean volumeSpike     = vol >= BREAKOUT_VOLUME_MULTIPLIER * baselineAvgVol;

                        if (breaksPriorRed && breaksHOD && volumeSpike) {
                            lastSetup = new FlagSetup(bar, poleTopHigh, flagLow);
                            log.info("[{}] PASS: Breakout bar confirmed at {} " +
                                     "(close={}, priorRedHigh={}, poleTopHigh={}, vol={}, baselineAvgVol={})",
                                    symbol, bar.time(),
                                    String.format("%.4f", bar.close()),
                                    String.format("%.4f", priorRedHigh),
                                    String.format("%.4f", poleTopHigh),
                                    String.format("%.0f", vol),
                                    String.format("%.0f", baselineAvgVol));
                            state = State.IDLE; // reset and continue — last setup wins
                        } else {
                            if (!breaksPriorRed) {
                                log.info("[{}] Breakout condition FAILED: close ({}) <= priorRedHigh ({})",
                                        symbol,
                                        String.format("%.4f", bar.close()),
                                        String.format("%.4f", priorRedHigh));
                            }
                            if (!breaksHOD) {
                                log.info("[{}] Breakout condition FAILED: high ({}) <= poleTopHigh ({})",
                                        symbol,
                                        String.format("%.4f", bar.high()),
                                        String.format("%.4f", poleTopHigh));
                            }
                            if (!volumeSpike) {
                                log.info("[{}] Breakout condition FAILED: vol ({}) < {}× baselineAvgVol ({})",
                                        symbol,
                                        String.format("%.0f", vol),
                                        BREAKOUT_VOLUME_MULTIPLIER,
                                        String.format("%.0f", baselineAvgVol));
                            }
                            // Check if this failed-breakout green qualifies as a new pole start
                            if (body >= POLE_BODY_MULTIPLIER * avgBody && vol >= POLE_VOLUME_MULTIPLIER * avgVol) {
                                log.info("[{}] PASS: Failed breakout green qualifies as new pole start at {} — switching to POLE_FORMING",
                                        symbol, bar.time());
                                baselineAvgBody = avgBody;
                                baselineAvgVol  = avgVol;
                                poleCandles = new ArrayList<>();
                                poleCandles.add(bar);
                                flagBarsTotal = 0;
                                state = State.POLE_FORMING;
                            }
                            // else stay in FLAG_FORMING
                        }
                    }
                }
            }
        }

        return lastSetup;
    }

    // -------------------------------------------------------------------------
    // Rolling-average helpers
    // -------------------------------------------------------------------------

    private boolean isGreen(Bar bar) {
        return bar.close() > bar.open();
    }

    private boolean isRed(Bar bar) {
        return bar.close() <= bar.open();
    }

    /**
     * Mean body size over the last {@code ROLLING_WINDOW} bars ending at {@code endExclusive}.
     * Returns 0.0 if fewer than {@code ROLLING_MIN_BARS} bars are available (not yet trusted).
     * Only pass market-hours bar lists — pre-market bars must be excluded by the caller.
     */
    private double avgBodySize(List<Bar> bars, int endExclusive) {
        int from = Math.max(0, endExclusive - ROLLING_WINDOW);
        if (endExclusive - from < ROLLING_MIN_BARS) return 0.0;
        double sum = 0.0;
        for (int i = from; i < endExclusive; i++) {
            Bar b = bars.get(i);
            sum += Math.abs(b.close() - b.open());
        }
        return sum / (endExclusive - from);
    }

    /**
     * Mean volume over the last {@code ROLLING_WINDOW} bars ending at {@code endExclusive}.
     * Returns 0.0 if fewer than {@code ROLLING_MIN_BARS} bars are available (not yet trusted).
     * Only pass market-hours bar lists — pre-market bars must be excluded by the caller.
     */
    private double avgVolume(List<Bar> bars, int endExclusive) {
        int from = Math.max(0, endExclusive - ROLLING_WINDOW);
        if (endExclusive - from < ROLLING_MIN_BARS) return 0.0;
        double sum = 0.0;
        for (int i = from; i < endExclusive; i++) {
            sum += bars.get(i).volume().value().doubleValue();
        }
        return sum / (endExclusive - from);
    }

    /**
     * Parses the time component from an IBKR bar timestamp {@code "yyyyMMdd  HH:mm:ss"}.
     * Returns {@link LocalTime#MIN} and logs a warning on failure.
     */
    private LocalTime parseBarTime(String timestamp) {
        try {
            String[] tokens = timestamp.trim().split("\\s+");
            return LocalTime.parse(tokens[1], DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) {
            log.warn("[BullFlagBreakout] Failed to parse bar timestamp '{}': {}", timestamp, e.getMessage());
            return LocalTime.MIN;
        }
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
        filterOptions.add(new TagValue("volumeVsAvgAbove", "3")); // 3× relative volume

        log.debug("[BullFlagBreakout] Scanning with price=$1.50-$20, volumeVsAvgAbove=3");
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
    // End-of-day cleanup
    // -------------------------------------------------------------------------

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
