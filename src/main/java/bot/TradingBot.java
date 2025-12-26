package bot;
import com.ib.client.*;
import com.ib.client.protobuf.*;
import ibkr.EWrapperImpl;
import ibkr.IBKRConnection;
import ibkr.model.MarketDataInput;
import ibkr.model.ScanData;
import ibkr.model.TickPriceOutput;
import strategy.LowFloatMomentum;
import strategy.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class TradingBot{
    public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
        IBKRConnection ibkrConnection = new IBKRConnection();
        ibkrConnection.onConnect();
        ibkrConnection.reqMarketDataType(MarketDataType.DELAYED);

        Strategy myStrategy = new LowFloatMomentum(ibkrConnection);

        myStrategy.run();

//        ibkrConnection.onDisconnect();


    }
}
