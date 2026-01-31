package ibkr.model;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderOutput {
    int orderId;
    Contract contract;
    Order order;
    OrderState orderState;
}
