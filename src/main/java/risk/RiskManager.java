package risk;

import com.ib.client.*;
import ibkr.model.OrderOutput;
import ibkr.model.PositionOutput;
import ibkr.model.TickPriceOutput;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class RiskManager {

    //get trade info and this file will check if only one stock can be traded at one time
    // check rules like certain time do we still have trades - done
    // check if given stock has liquidity issues - illiquid - done
    // check if stock halt?? -is issue? - done
    // can issue max trades per day
    //TODO: can add in the future stop all trades if S&P 500 in huge drawdown

    // Maximum acceptable spread as percentage of mid price (e.g., 0.03 = 3%)
    private static final double MAX_SPREAD_PERCENT = 0.03;

    /**
     * Checks if the bid-ask spread is acceptable for trading.
     * Wide spreads indicate low liquidity and higher trading costs.
     *
     * @param bidPrice Current bid price
     * @param askPrice Current ask price
     * @return true if spread is acceptable, false if too wide
     */
    public boolean checkBidAskSpread(double bidPrice, double askPrice) {
        return checkBidAskSpread(bidPrice, askPrice, MAX_SPREAD_PERCENT);
    }

    /**
     * Checks if the bid-ask spread is acceptable with a custom threshold.
     *
     * @param bidPrice         Current bid price
     * @param askPrice         Current ask price
     * @param maxSpreadPercent Maximum acceptable spread as decimal (e.g., 0.03 for 3%)
     * @return true if spread is acceptable, false if too wide
     */
    public boolean checkBidAskSpread(double bidPrice, double askPrice, double maxSpreadPercent) {
        if (bidPrice <= 0 || askPrice <= 0 || bidPrice >= askPrice) {
            System.out.println("RiskManager: Invalid bid/ask prices - Bid: " + bidPrice + ", Ask: " + askPrice);
            return false;
        }

        double spread = askPrice - bidPrice;
        double midPrice = (bidPrice + askPrice) / 2;
        double spreadPercent = spread / midPrice;

        System.out.printf("RiskManager: Bid: %.4f, Ask: %.4f, Spread: %.4f (%.2f%%)%n",
                bidPrice, askPrice, spread, spreadPercent * 100);

        if (spreadPercent > maxSpreadPercent) {
            System.out.printf("RiskManager: Spread too wide! %.2f%% > %.2f%% max%n",
                    spreadPercent * 100, maxSpreadPercent * 100);
            return false;
        }

        return true;
    }

    /**
     * Extracts bid and ask from tick prices and checks if spread is acceptable.
     *
     * @param tickPrices List of tick prices from reqMarketData
     * @return true if spread is acceptable, false if too wide or missing data
     */
    public boolean checkBidAskSpread(List<TickPriceOutput> tickPrices) {
        Double bidPrice = null;
        Double askPrice = null;

        for (TickPriceOutput tick : tickPrices) {
            if (tick.getField() == TickType.BID.index()) {
                bidPrice = tick.getPrice();
            } else if (tick.getField() == TickType.ASK.index()) {
                askPrice = tick.getPrice();
            }
        }

        if (bidPrice == null || askPrice == null) {
            System.out.println("RiskManager: Missing bid or ask price from tick data");
            return false;
        }

        return checkBidAskSpread(bidPrice, askPrice);
    }

    public boolean isStockTradeable(List<TickPriceOutput> tickPrices, String tradingHours) {
        boolean pastLimit = tickPrices.stream()
                .filter(x -> x.getAttribs() != null)
                .anyMatch(x -> x.getAttribs().pastLimit());

        if (pastLimit){
            System.out.println("RiskManager: Stock at LULD limit - potential halt");
            return false;
        }

        // Use Eastern Time (US market)
        ZoneId eastern = ZoneId.of("America/New_York");
        LocalDateTime now = LocalDateTime.now(eastern);
        String today = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        System.out.println(tradingHours);
        String[] days = tradingHours.split(";");

        for (String day : days) {
            if (day.startsWith(today)){
                if (day.contains("CLOSED")) {
                    System.out.println("RiskManager: Market CLOSED today");
                    return false;
                }

                //SEE IF THIS IS NEEDED
                // Parse format: "20260105:0400-20260105:2000"
                // Extract time portion after first colon
                String timePart = day.substring(day.indexOf(":") + 1);  // "0400-20260105:2000"
                String[] parts = timePart.split("-");

                // Open time: "0400"
                String openTimeStr = parts[0];
                // Close time: "20260105:2000" -> extract "2000"
                String closeTimeStr = parts[1].substring(parts[1].indexOf(":") + 1);

                LocalTime openTime = LocalTime.parse(openTimeStr, DateTimeFormatter.ofPattern("HHmm"));
                LocalTime closeTime = LocalTime.parse(closeTimeStr, DateTimeFormatter.ofPattern("HHmm"));
                LocalTime currentTime = now.toLocalTime();

                System.out.printf("RiskManager: Market hours %s - %s, Current: %s%n",
                        openTime, closeTime, currentTime);

                boolean isOpen = !currentTime.isBefore(openTime) && currentTime.isBefore(closeTime);

                if (!isOpen) {
                    System.out.println("RiskManager: Outside trading hours");
                }

                return isOpen;
            }
        }

        System.out.println("RiskManager: Today not found in trading schedule");
        return false;
    }

    public boolean hasOrder(List<OrderOutput> orders, String symbol) {
        return orders.stream()
                .anyMatch(x -> x.getContract().symbol().equals(symbol));
    }

    public boolean hasPosition(List<PositionOutput> positions, String symbol) {
        return positions.stream()
                .anyMatch(x -> x.getContract().symbol().equals(symbol));

    }

}