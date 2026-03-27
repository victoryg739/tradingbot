package monitoring;

import ibkr.IBKRConnection;
import ibkr.model.PositionOutput;
import trade.TradeJournal;
import trade.TradeRecord;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class JsonBuilder {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String statusJson(IBKRConnection.ConnectionState state,
                                    int lastErrorCode, String lastErrorMsg,
                                    String tradingMode, long uptimeSeconds) {
        return "{"
            + "\"connectionState\":" + jsonString(state.name()) + ","
            + "\"lastErrorCode\":" + lastErrorCode + ","
            + "\"lastErrorMsg\":" + jsonString(lastErrorMsg) + ","
            + "\"tradingMode\":" + jsonString(tradingMode) + ","
            + "\"uptimeSeconds\":" + uptimeSeconds
            + "}";
    }

    public static String positionsJson(List<PositionOutput> positions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < positions.size(); i++) {
            PositionOutput p = positions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"account\":").append(jsonString(p.getAccount())).append(",")
              .append("\"symbol\":").append(jsonString(p.getContract().symbol())).append(",")
              .append("\"secType\":").append(jsonString(p.getContract().secType() != null ? p.getContract().secType().name() : null)).append(",")
              .append("\"currency\":").append(jsonString(p.getContract().currency())).append(",")
              .append("\"position\":").append(p.getPos()).append(",")
              .append("\"avgCost\":").append(p.getAvgCost())
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public static String tradesJson(List<TradeRecord> trades,
                                    Map<String, TradeJournal.StrategySummary> summaries) {
        StringBuilder sb = new StringBuilder("{");

        sb.append("\"strategySummaries\":[");
        boolean first = true;
        for (TradeJournal.StrategySummary s : summaries.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"strategy\":").append(jsonString(s.strategy())).append(",")
              .append("\"totalTrades\":").append(s.totalTrades()).append(",")
              .append("\"winningTrades\":").append(s.winningTrades()).append(",")
              .append("\"totalNetPnL\":").append(round2(s.totalNetPnL())).append(",")
              .append("\"totalCommission\":").append(round2(s.totalCommission()))
              .append("}");
        }
        sb.append("],");

        sb.append("\"trades\":[");
        for (int i = 0; i < trades.size(); i++) {
            TradeRecord t = trades.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"execId\":").append(jsonString(t.getExecId())).append(",")
              .append("\"symbol\":").append(jsonString(t.getSymbol())).append(",")
              .append("\"strategy\":").append(jsonString(t.getStrategy())).append(",")
              .append("\"time\":").append(jsonString(t.getTime() != null ? t.getTime().format(FMT) : "")).append(",")
              .append("\"side\":").append(jsonString(t.getSide())).append(",")
              .append("\"shares\":").append(t.getShares()).append(",")
              .append("\"fillPrice\":").append(t.getFillPrice()).append(",")
              .append("\"commission\":").append(round2(t.getCommission())).append(",")
              .append("\"realizedPnL\":").append(round2(t.getRealizedPnL())).append(",")
              .append("\"netPnL\":").append(round2(t.getNetPnL())).append(",")
              .append("\"isClosingTrade\":").append(t.isClosingTrade())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
