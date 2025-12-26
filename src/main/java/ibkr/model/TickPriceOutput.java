package ibkr.model;

import com.ib.client.TickAttrib;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TickPriceOutput {
    private int field;
    double price;
    TickAttrib attribs;
}
