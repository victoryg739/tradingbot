package ibkr.model;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.TagValue;
import com.ib.client.Types;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
@Builder
public class HistoricalDataInput {
    /**
     *  1. Contract -> Object describing the instrument
     *  2. String endDateTime -> "YYYYMMDD HH:MM:SS [TZ]" (TZ optional) or  "" for now
     *  3. String durationStr -> how far back from endDateTime to fetch. format: number + unit (e.g., "30 D", "1 M", "1 Y", "2 W", "3600 S").
     *  4. String barSizeSetting -> the bar (e.g. "1 sec, "1 min", "5 mins", "1 month" ,etc)
     *  5. String whatToShow -> data type to return (e.g "TRADES", "BID", "ASK", "HISTORICAL_VOLATILITY") Use "TRADES" for normal OHLC of trades.
     *   All whatToShow Options ->
     *   "TRADES"           // Actual executed trades (DEFAULT - use this)
     *   "MIDPOINT"         // Average of bid and ask
     *   "BID"              // What buyers are willing to pay
     *   "ASK"              // What sellers are asking
     *   "BID_ASK"          // Both bid and ask
     *   "HISTORICAL_VOLATILITY"  // Calculated volatility
     *   "OPTION_IMPLIED_VOLATILITY"  // IV for options
     *   "REBATE_RATE"      // For short selling
     *   "FEE_RATE"         // Borrowing fees
     *   "YIELD_BID"        // For bonds
     *   "YIELD_ASK"        // For bonds
     *  6. int useRTH -> 1 = return data only for Regular Trading Hours (RTH); 0 = return data for all hours (including pre-/post-market).
     *  7. int formatData -> controls the date/time format returned in bars (1 - human-readable date/time strings, 2 - epoch-style)
     *  8. boolean keepUpToDate -> if true, the API attempts to keep the requested bars updated in real time. If false you get a one-shot historical dump.
     *  9. List<TagValue> chartOptions - optional chart options
     */
    public enum UseRth {
        REGULAR_TRADING_HOURS,
        ALL_HOURS
    }
    @Getter
    public enum FormatData{
        HUMAN_READERABLE(1),
        EPOCH_STYLE(2);

        private final int value;

        FormatData(int value) {
            this.value = value;
        }

    }

    private Contract contract;
    String endDateTime;
    String durationStr;
    private Types.BarSize barSize;
    private Types.WhatToShow whatToShow;
    private UseRth useRth ;
    private FormatData formatData;
    private boolean keepUpToDate;
    List<TagValue> chartOptions;

}
