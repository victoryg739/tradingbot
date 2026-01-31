package indicators;

import com.ib.client.Bar;

import java.util.List;

public interface Indicator {
    double calculate ();
    List<Double> calculateSeries();
}
