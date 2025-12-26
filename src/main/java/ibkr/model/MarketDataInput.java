package ibkr.model;

import com.ib.client.Contract;
import com.ib.client.TagValue;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MarketDataInput {
    /** 1. Contract
        2. genericTickList
         A string of comma-separated tick type codes
         requesting additional data beyond defaults. The documentation
         mentions "233,236" as an example for requesting "RTVolume (Time &
         Sales) and shortable" data.
             final String ALL_GENERIC_TICK_TAGS = "100,101,104,106,165,221,232,236,258,293,294,295,318,411,460,619";

             https://interactivebrokers.github.io/tws-api/tick_types.html
            These are all the generic tick types that IBKR's API supports. IBKR defined this list — it's their official set of optional market data fields.

            | ID            | Why Included                                |
            |---------------|---------------------------------------------|
            | 100, 101      | Options traders need volume & open interest |
            | 104, 106      | Volatility data for options pricing         |
            | 165           | Misc stats (avg volume) for analysis        |
            | 221           | Mark price for margin calculations          |
            | 232           | 13-week low for technical analysis          |
            | 236           | Shortable shares for short sellers          |
            | 258           | Fundamental data (P/E, EPS) for investors   |
            | 293, 294, 295 | Trade/volume rates for momentum analysis    |
            | 318           | Last RTH trade for overnight gaps           |
            | 411           | Real-time historical volatility             |
            | 460           | Bond factor (for fixed income)              |
            | 619           | IPO price estimates                         |

     3.snapshot
         Boolean parameter that, when set to true, returns a
         single market data snapshot within 11 seconds rather than
         continuous streaming updates. "Snapshot requests can only be made
         for the default tick types; no generic ticks can be specified."
     4.**regulatorySnapshot**:
     Boolean parameter enabling regulatory
     snapshot requests for US stocks and options. When true, each
     request incurs a $0.01 USD fee. This requires TWS/IBG v963 and
     API 973.02 or higher.
     5.mktDataOptions: Allows additional configuration options for
     the market data request.

     **/

    private Contract contract;
    private String genericTickList;
    private boolean snapshot;
    private boolean regulatorySnapshot;
    private List<TagValue>  tagValues;

}
