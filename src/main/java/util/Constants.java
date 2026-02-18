package util;

import java.time.LocalTime;
import java.time.ZoneId;

public class Constants {
    // prevent init
    private Constants() {}

    // Fixed ID for positions
    public static final int POSITIONS_REQ_ID = 0;

    // Fixed ID for open orders
    public static final int OPEN_ORDERS_REQ_ID = 1;

    // Stock priced $1.00 or above
    public static final double STANDARD_TICK_SIZE = 0.01;

    // Stock priced below $1.00
    public static final double SUB_DOLLAR_TICK_SIZE = 0.001;

    // How much risk % of the portfolio per trade
    public static final double RISK_PER_TRADE = 0.025;

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");
    public static LocalTime timeNow() {
        return LocalTime.now(EASTERN);
    }
    public static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    public static final LocalTime INTRADAY_CUTOFF = LocalTime.of(15, 30); // 30 min before close
}