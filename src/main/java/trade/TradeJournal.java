package trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory trade journal.
 * Captures fills and IBKR-computed realized P&L by correlating openOrder,
 * execDetails, and commissionAndFeesReport callbacks.
 */
public class TradeJournal {
    private static final Logger log = LoggerFactory.getLogger(TradeJournal.class);

    // --- Inner types ---

    public record OrderMeta(String strategy, String symbol) {}

    private record PendingExecution(
        String execId, int orderId, String side, double shares, double price, LocalDateTime time
    ) {}

    private record CommissionData(double commission, double realizedPnL) {}

    public record StrategySummary(
        String strategy,
        int totalTrades,
        int winningTrades,
        double totalNetPnL,
        double totalCommission
    ) {}

    // --- State ---

    /** orderId → {strategy, symbol} — populated by openOrder() */
    private final Map<Integer, OrderMeta> orderMetaMap = new ConcurrentHashMap<>();

    /** execId → execution waiting for commission report */
    private final Map<String, PendingExecution> pendingExecMap = new ConcurrentHashMap<>();

    /** execId → commission report waiting for execution (handles race where commission arrives first) */
    private final Map<String, CommissionData> pendingCommissionMap = new ConcurrentHashMap<>();

    /** Finalized trade records */
    private final List<TradeRecord> completedTrades = new CopyOnWriteArrayList<>();

    // --- Public API ---

    /** Called from openOrder() to register strategy/symbol for an order ID. */
    public void recordOrderMeta(int orderId, String strategy, String symbol) {
        if (strategy == null || strategy.isBlank()) return;
        orderMetaMap.put(orderId, new OrderMeta(strategy, symbol));
        log.debug("TradeJournal: registered orderId={} strategy='{}' symbol='{}'", orderId, strategy, symbol);
    }

    /** Called from execDetails() when a fill arrives. */
    public void recordExecution(String execId, int orderId, String side, double shares, double price, LocalDateTime time) {
        PendingExecution exec = new PendingExecution(execId, orderId, side, shares, price, time);

        // Check if commission report already arrived for this execId
        CommissionData commission = pendingCommissionMap.remove(execId);
        if (commission != null) {
            finalizeTrade(exec, commission);
        } else {
            pendingExecMap.put(execId, exec);
        }
        log.debug("TradeJournal: recorded execution execId={} orderId={} side={} shares={} price={}", execId, orderId, side, shares, price);
    }

    /** Called from commissionAndFeesReport(). Triggers trade completion when the matching execution exists. */
    public void recordCommission(String execId, double commission, double realizedPnL) {
        CommissionData commData = new CommissionData(commission, realizedPnL);

        // Check if execution already arrived for this execId
        PendingExecution exec = pendingExecMap.remove(execId);
        if (exec != null) {
            finalizeTrade(exec, commData);
        } else {
            pendingCommissionMap.put(execId, commData);
        }
        log.debug("TradeJournal: recorded commission execId={} commission={} realizedPnL={}", execId, commission, realizedPnL);
    }

    public List<TradeRecord> getCompletedTrades() {
        return Collections.unmodifiableList(completedTrades);
    }

    /** Aggregates completed trades per strategy. */
    public Map<String, StrategySummary> getStrategySummaries() {
        Map<String, List<TradeRecord>> byStrategy = new LinkedHashMap<>();
        for (TradeRecord trade : completedTrades) {
            byStrategy.computeIfAbsent(trade.getStrategy(), k -> new ArrayList<>()).add(trade);
        }

        Map<String, StrategySummary> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, List<TradeRecord>> entry : byStrategy.entrySet()) {
            String strategy = entry.getKey();
            List<TradeRecord> trades = entry.getValue();
            int totalTrades = trades.size();
            int winningTrades = (int) trades.stream().filter(t -> t.isClosingTrade() && t.getNetPnL() > 0).count();
            double totalNetPnL = trades.stream().filter(TradeRecord::isClosingTrade).mapToDouble(TradeRecord::getNetPnL).sum();
            double totalCommission = trades.stream().mapToDouble(TradeRecord::getCommission).sum();
            summaries.put(strategy, new StrategySummary(strategy, totalTrades, winningTrades, totalNetPnL, totalCommission));
        }
        return summaries;
    }

    // --- Private helpers ---

    private void finalizeTrade(PendingExecution exec, CommissionData commData) {
        // IBKR sets realizedPnL = Double.MAX_VALUE for opening fills
        boolean isClosing = commData.realizedPnL() < Double.MAX_VALUE / 2;
        double realizedPnL = isClosing ? commData.realizedPnL() : 0.0;
        double netPnL = isClosing ? realizedPnL - commData.commission() : 0.0;

        OrderMeta meta = orderMetaMap.get(exec.orderId());
        String strategy = meta != null ? meta.strategy() : "Unknown";
        String symbol = meta != null ? meta.symbol() : "Unknown";

        TradeRecord record = TradeRecord.builder()
                .execId(exec.execId())
                .symbol(symbol)
                .strategy(strategy)
                .time(exec.time())
                .side(exec.side())
                .shares(exec.shares())
                .fillPrice(exec.price())
                .commission(commData.commission())
                .realizedPnL(realizedPnL)
                .netPnL(netPnL)
                .isClosingTrade(isClosing)
                .build();

        completedTrades.add(record);
        log.info("TradeJournal: finalized trade execId={} symbol={} strategy='{}' side={} closing={} netPnL={}",
                exec.execId(), symbol, strategy, exec.side(), isClosing, netPnL);
    }
}
