package bot;
import com.ib.client.*;
import com.ib.client.protobuf.*;
import ibkr.EWrapperImpl;
import ibkr.IBKRConnection;
import ibkr.model.ScanData;
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
//        ibkrConnection.reqHistoricalData();
//        ibkrConnection.reqMarketData();
//        List<ScanData> scanData = ibkrConnection.marketScan();
        Strategy myStrategy = new LowFloatMomentum(ibkrConnection);

        myStrategy.buy();

//        ibkrConnection.onDisconnect();


    }
}
