package trade;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TradeRecord {
    String execId;
    String symbol;
    String strategy;
    LocalDateTime time;
    String side;          // "BOT" or "SLD"
    double shares;
    double fillPrice;
    double commission;
    double realizedPnL;   // IBKR-computed (only meaningful for closing fills)
    double netPnL;        // realizedPnL - commission
    boolean isClosingTrade;
}
