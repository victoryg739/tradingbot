package bot;
import com.ib.client.*;
import com.ib.client.protobuf.*;
import ibkr.EWrapperImpl;
import ibkr.IBKRConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TradingBot{
    static void main() throws InterruptedException, ExecutionException {
        IBKRConnection ibkrConnection = new IBKRConnection();
        ibkrConnection.onConnect();
        ibkrConnection.reqMarketData();
//        ibkrConnection.onDisconnect();


    }
}
