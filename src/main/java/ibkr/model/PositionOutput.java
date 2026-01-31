package ibkr.model;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PositionOutput {
    private String account;
    private Contract contract;
    private Decimal pos;
    private double avgCost;
}
