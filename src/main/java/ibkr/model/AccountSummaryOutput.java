package ibkr.model;

import com.ib.client.TickAttrib;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AccountSummaryOutput {
    String account;
    String tag;
    String value;
    String currency;

    public static double getValueWithTag(List<AccountSummaryOutput> accountSummaryOutputList, String tagName) {
      return accountSummaryOutputList.stream()
                .filter(x -> x.getTag().equals(tagName))
                .map(AccountSummaryOutput::getValue)
                .mapToDouble(Double::parseDouble)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(tagName + " not found"));
    }
}