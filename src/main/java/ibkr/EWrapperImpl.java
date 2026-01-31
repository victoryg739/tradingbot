package ibkr;

import com.ib.client.*;
import com.ib.client.protobuf.*;
import data.RequestTracker;
import data.RequestTrackerManager;
import ibkr.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Constants;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/*
EWrapper - This is the "incoming" side. It's the class that receives responses from TWS.
when TWS sends back market data, order confirmations, or error messages, EWrapper methods get called with that information.
You need to implement (write code for) these methods to handle the responses.
*/
public class EWrapperImpl implements EWrapper {
    private static final Logger log = LoggerFactory.getLogger(EWrapperImpl.class);

    private EReaderSignal readerSignal;
    private EClientSocket clientSocket;
//    protected int currentOrderId = -1;
    // Stored Order ID - no need for constant retriever and its atomic
    private final AtomicInteger currentOrderId = new AtomicInteger(-1);
    //! [socket_declare]

    private RequestTrackerManager requestTrackerManager;

//    private Map<Integer, Set<Integer>> receivedTickTypes = new ConcurrentHashMap<>();
//    private Set<Integer> requiredTicks = Set.of(
//            TickType.BID.index(),    // 1
//            TickType.ASK.index(),    // 2
//            TickType.LAST.index()    // 4
//    );

    //! [socket_init]
    public EWrapperImpl(RequestTrackerManager requestTrackerManager) {
        readerSignal = new EJavaSignal();
        clientSocket = new EClientSocket(this, readerSignal);
        this.requestTrackerManager = requestTrackerManager;
    }
    //! [socket_init]
    public EClientSocket getClient() {
        return clientSocket;
    }

    public EReaderSignal getSignal() {
        return readerSignal;
    }

    public synchronized int getAndIncrementOrderId() {
        return currentOrderId.getAndIncrement();
    }

    /**
     * Atomically reserves multiple sequential order IDs.
     * Used for bracket orders where parent and child orders need sequential IDs.
     * To prevent the following race condition:
     *   1. Thread A calls placeBracketOrders()
     *     - Gets parentOrderId = 1000 (currentOrderId becomes 1001)
     *     - childOrderId1 = 1001
     *     - childOrderId2 = 1002
     *   2. But BEFORE thread A places the orders, Thread B also calls placeBracketOrders()
     *     - Gets parentOrderId = 1001 (currentOrderId becomes 1002)
     *     - childOrderId1 = 1002
     *     - childOrderId2 = 1003
     *   3. Now there's a conflict:
     *     - Thread A expects to use 1000, 1001, 1002
     *     - Thread B expects to use 1001, 1002, 1003
     *     - IDs 1001 and 1002 are duplicated!
     * @param count Number of IDs to reserve
     * @return The first ID in the sequence
     */
    public synchronized int reserveOrderIds(int count) {
        int firstId = currentOrderId.get();
        currentOrderId.addAndGet(count);
        return firstId;
    }

    public synchronized void waitForInitOrderId() throws InterruptedException {
        while (currentOrderId.get() == -1) {
            wait(5000);
            /**
             * What wait() do?
             * 1. Release the lock on this object -> the whole method (acquired by sychronized)
             * 2. Current thread pauses execution and waits
             * 3. Can be woken up by notify()/notifyAll() or timeout of 5 seconds
             * */
        }
    }

    //! [tickprice]
    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        //market data  -> tick uses this
        //only work when snapshot == True now

        RequestTracker<TickPriceOutput> tickPriceTracker = requestTrackerManager.getTracker(TickPriceOutput.class);

        TickPriceOutput tickPriceOutput = TickPriceOutput.builder()
                .field(field)
                .price(price)
                .attribs(attribs)
                .build();

        tickPriceTracker.add(tickerId,tickPriceOutput);

//        System.out.println("Tick Price: " + EWrapperMsgGenerator.tickPrice( tickerId, field, price, attribs));
    }
    //! [tickprice]

    @Override
    public void tickSize(int tickerId, int field, Decimal size) {
        log.trace("Tick Size: tickerId={}, field={}, size={}", tickerId, field, size);
    }

    @Override
    public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol, double delta, double optPrice,
                                      double pvDividend, double gamma, double vega, double theta, double undPrice) {
        log.trace("TickOptionComputation: tickerId={}, field={}, delta={}, optPrice={}", tickerId, field, delta, optPrice);
    }

    @Override
    public void tickGeneric(int tickerId, int tickType, double value) {
        log.trace("Tick Generic: tickerId={}, tickType={}, value={}", tickerId, tickType, value);
    }

    @Override
    public void tickString(int tickerId, int tickType, String value) {
        log.trace("Tick String: tickerId={}, tickType={}, value={}", tickerId, tickType, value);
    }

    @Override
    public void tickEFP(int tickerId, int tickType, double basisPoints, String formattedBasisPoints, double impliedFuture, int holdDays,
                        String futureLastTradeDate, double dividendImpact, double dividendsToLastTradeDate) {
        log.trace("TickEFP: tickerId={}, tickType={}, basisPoints={}", tickerId, tickType, basisPoints);
    }
    private static final Logger orderLog = LoggerFactory.getLogger("ORDER_AUDIT");

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId,
                            double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        // Log to both regular log and order audit log
        orderLog.info("ORDER_STATUS | orderId={} | status={} | filled={} | remaining={} | avgFillPrice={} | lastFillPrice={} | parentId={} | whyHeld={}",
                orderId, status, filled, remaining, avgFillPrice, lastFillPrice, parentId, whyHeld);

//        // Debug level for regular logs
//        log.debug("Order status update: orderId={}, status={}, filled={}, remaining={}",
//                orderId, status, filled, remaining);
    }

    //! [openorder]
    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        RequestTracker<OrderOutput> orderOutputTracker = requestTrackerManager.getTracker(OrderOutput.class);
        OrderOutput orderOutput = OrderOutput.builder()
                .orderId(orderId)
                .contract(contract)
                .order(order)
                .orderState(orderState)
                .build();

        orderOutputTracker.add(Constants.OPEN_ORDERS_REQ_ID, orderOutput);

//        System.out.println(EWrapperMsgGenerator.openOrder(orderId, contract, order, orderState));
    }
    //! [openorder]

    //! [openorderend]
    @Override
    public void openOrderEnd() {
        RequestTracker<OrderOutput> orderOutputTracker = requestTrackerManager.getTracker(OrderOutput.class);
        orderOutputTracker.complete(Constants.OPEN_ORDERS_REQ_ID);
//        System.out.println("Open Order End: " + EWrapperMsgGenerator.openOrderEnd());
    }
    //! [openorderend]

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        log.debug("Account value update: key={}, value={}, currency={}, account={}", key, value, currency, accountName);
    }

    @Override
    public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost,
                                double unrealizedPNL, double realizedPNL, String accountName) {
        log.debug("Portfolio update: symbol={}, position={}, marketValue={}, unrealizedPNL={}",
                contract.symbol(), position, marketValue, unrealizedPNL);
    }

    @Override
    public void updateAccountTime(String timeStamp) {
        log.trace("Account time update: {}", timeStamp);
    }

    @Override
    public void accountDownloadEnd(String accountName) {
        log.debug("Account download complete: {}", accountName);
    }

    @Override
    public synchronized void nextValidId(int orderId) {
        log.info("Received next valid order ID from TWS: {}", orderId);
        currentOrderId.set(orderId);
        notifyAll();  // Wake up threads waiting in waitForInitOrderId()
    }

    //! [contractdetails]
    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        RequestTracker<ContractDetails> contractDetailsTracker = requestTrackerManager.getTracker(ContractDetails.class);
        contractDetailsTracker.add(reqId, contractDetails);
//        System.out.println(EWrapperMsgGenerator.contractDetails(reqId, contractDetails));
    }
    //! [contractdetails]
    @Override
    public void bondContractDetails(int reqId, ContractDetails contractDetails) {
        log.debug("Bond contract details: reqId={}, symbol={}", reqId, contractDetails.contract().symbol());
    }
    //! [contractdetailsend]
    @Override
    public void contractDetailsEnd(int reqId) {
        RequestTracker<ContractDetails> contractDetailsTracker = requestTrackerManager.getTracker(ContractDetails.class);
        contractDetailsTracker.complete(reqId);
//        System.out.println("Contract Details End: " + EWrapperMsgGenerator.contractDetailsEnd(reqId));
    }
    //! [contractdetailsend]

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        orderLog.info("EXEC_DETAILS | reqId={} | symbol={} | side={} | shares={} | price={} | execId={}",
                reqId, contract.symbol(), execution.side(), execution.shares(), execution.price(), execution.execId());
    }

    @Override
    public void execDetailsEnd(int reqId) {
        log.debug("Execution details end: reqId={}", reqId);
    }

    @Override
    public void updateMktDepth(int tickerId, int position, int operation, int side, double price, Decimal size) {
        log.trace("Market depth: tickerId={}, position={}, side={}, price={}, size={}", tickerId, position, side, price, size);
    }

    @Override
    public void updateMktDepthL2(int tickerId, int position, String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth) {
        log.trace("Market depth L2: tickerId={}, marketMaker={}, price={}, size={}", tickerId, marketMaker, price, size);
    }

    @Override
    public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {
        log.info("News Bulletin: msgId={}, type={}, message={}", msgId, msgType, message);
    }

    @Override
    public void managedAccounts(String accountsList) {
        log.info("Managed accounts: {}", accountsList);
    }

    @Override
    public void receiveFA(int faDataType, String xml) {
        log.debug("Receiving FA: type={}, xmlLength={}", faDataType, xml != null ? xml.length() : 0);
    }

    @Override
    public void historicalData(int reqId, Bar bar) {
        RequestTracker<Bar> historicalTracker = requestTrackerManager.getTracker(Bar.class);
        historicalTracker.add(reqId, bar);
        log.trace("HistoricalData reqId={}: time={}, O={}, H={}, L={}, C={}, V={}",
                reqId, bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        RequestTracker<Bar> historicalTracker = requestTrackerManager.getTracker(Bar.class);
        historicalTracker.complete(reqId);
        log.debug("HistoricalData complete: reqId={}, range={} to {}", reqId, startDateStr, endDateStr);
    }

    @Override
    public void scannerParameters(String xml) {
        log.debug("Scanner parameters received (length={})", xml.length());
    }

    //! [scannerdata]
    @Override
    public void scannerData(int reqId, int rank, ContractDetails contractDetails, String distance, String benchmark, String projection, String legsStr) {
        ScanData scanData = ScanData.builder().rank(rank)
                .contractDetails(contractDetails)
                .distance(distance)
                .benchmark(benchmark)
                .projection(projection)
                .legsStr(legsStr)
                .build();
        RequestTracker<ScanData> scanDataTracker = requestTrackerManager.getTracker(ScanData.class);
        scanDataTracker.add(reqId, scanData);
//        System.out.println("ScannerData: " + EWrapperMsgGenerator.scannerData(reqId, rank, contractDetails, distance, benchmark, projection, legsStr));
    }
    //! [scannerdata]

    @Override
    public void scannerDataEnd(int reqId) {
        RequestTracker<ScanData> scanDataTracker = requestTrackerManager.getTracker(ScanData.class);
        scanDataTracker.complete(reqId);
        log.debug("Scanner data complete: reqId={}", reqId);
    }

    @Override
    public void realtimeBar(int reqId, long time, double open, double high, double low, double close, Decimal volume, Decimal wap, int count) {
        log.trace("RealTimeBar: reqId={}, O={}, H={}, L={}, C={}, V={}", reqId, open, high, low, close, volume);
    }

    @Override
    public void currentTime(long time) {
        log.debug("Server time: {}", time);
    }

    @Override
    public void fundamentalData(int reqId, String data) {
        log.debug("Fundamental data: reqId={}, dataLength={}", reqId, data != null ? data.length() : 0);
    }

    @Override
    public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {
        log.debug("Delta Neutral Validation: reqId={}", reqId);
    }
    //! [ticksnapshotend]
    @Override
    public void tickSnapshotEnd(int reqId) {
        RequestTracker<TickPriceOutput> tickPriceTracker = requestTrackerManager.getTracker(TickPriceOutput.class);

        tickPriceTracker.complete(reqId);

//        System.out.println("TickSnapshotEnd: " + EWrapperMsgGenerator.tickSnapshotEnd(reqId));
    }
    //! [ticksnapshotend]

    @Override
    public void marketDataType(int reqId, int marketDataType) {
        log.debug("Market data type: reqId={}, type={}", reqId, marketDataType);
    }

    @Override
    public void commissionAndFeesReport(CommissionAndFeesReport commissionAndFeesReport) {
        orderLog.info("COMMISSION | {}", EWrapperMsgGenerator.commissionAndFeesReport(commissionAndFeesReport));
    }

    //! [position]
    @Override
    public void position(String account, Contract contract, Decimal pos, double avgCost) {
        RequestTracker<PositionOutput> positionTracker = requestTrackerManager.getTracker(PositionOutput.class);
        PositionOutput positionOutput = PositionOutput.builder()
                .account(account)
                .contract(contract)
                .pos(pos)
                .avgCost(avgCost)
                .build();
        positionTracker.add(Constants.POSITIONS_REQ_ID, positionOutput);
//        System.out.println(EWrapperMsgGenerator.position(account, contract, pos, avgCost));
    }
    //! [position]

    //! [positionend]
    @Override
    public void positionEnd() {
        RequestTracker<PositionOutput> positionTracker = requestTrackerManager.getTracker(PositionOutput.class);
        positionTracker.complete(Constants.POSITIONS_REQ_ID);
//        System.out.println("Position End: " + EWrapperMsgGenerator.positionEnd());
    }
    //! [positionend]

    //! [accountsummary]
    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        RequestTracker<AccountSummaryOutput> accountSummaryTracker = requestTrackerManager.getTracker(AccountSummaryOutput.class);
        AccountSummaryOutput accountSummaryOutput = AccountSummaryOutput.builder()
                .account(account)
                .tag(tag)
                .value(value)
                .currency(currency)
                .build();
        accountSummaryTracker.add(reqId, accountSummaryOutput);

//        System.out.println(EWrapperMsgGenerator.accountSummary(reqId, account, tag, value, currency));
    }
    //! [accountsummary]

    //! [accountsummaryend]
    @Override
    public void accountSummaryEnd(int reqId) {
        RequestTracker<AccountSummaryOutput> accountSummaryTracker = requestTrackerManager.getTracker(AccountSummaryOutput.class);
        accountSummaryTracker.complete(reqId);

//        System.out.println("Account Summary End. Req Id: " + EWrapperMsgGenerator.accountSummaryEnd(reqId));
    }
    //! [accountsummaryend]
    @Override
    public void verifyMessageAPI(String apiData) {
        log.debug("verifyMessageAPI: {}", apiData);
    }

    @Override
    public void verifyCompleted(boolean isSuccessful, String errorText) {
        if (isSuccessful) {
            log.info("Verification completed successfully");
        } else {
            log.error("Verification failed: {}", errorText);
        }
    }

    @Override
    public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {
        log.debug("verifyAndAuthMessageAPI");
    }

    @Override
    public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {
        if (isSuccessful) {
            log.info("Verification and auth completed successfully");
        } else {
            log.error("Verification and auth failed: {}", errorText);
        }
    }

    @Override
    public void displayGroupList(int reqId, String groups) {
        log.debug("Display Group List: reqId={}, groups={}", reqId, groups);
    }

    @Override
    public void displayGroupUpdated(int reqId, String contractInfo) {
        log.debug("Display Group Updated: reqId={}, contractInfo={}", reqId, contractInfo);
    }
    @Override
    public void error(Exception e) {
        log.error("TWS Exception: {}", e.getMessage(), e);
    }

    @Override
    public void error(String str) {
        log.error("TWS Error: {}", str);
    }

    @Override
    public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        String errorTimeStr = errorTime != 0 ? Util.UnixMillisecondsToString(errorTime, "yyyyMMdd-HH:mm:ss") : "";

        // Categorize errors by code ranges for appropriate log levels
        // 2100-2169: Warnings (connectivity, market data farm connections)
        // 10000+: System messages (often informational)
        if (errorCode >= 2100 && errorCode < 2170) {
            log.warn("TWS Warning - id={}, code={}, msg={}, time={}", id, errorCode, errorMsg, errorTimeStr);
        } else if (errorCode >= 10000) {
            log.info("TWS Info - id={}, code={}, msg={}", id, errorCode, errorMsg);
        } else {
            // Real errors (order rejects, connection issues, etc.)
            if (advancedOrderRejectJson != null) {
                log.error("TWS Error - id={}, code={}, msg={}, time={}, orderReject={}",
                        id, errorCode, errorMsg, errorTimeStr, advancedOrderRejectJson);
            } else {
                log.error("TWS Error - id={}, code={}, msg={}, time={}", id, errorCode, errorMsg, errorTimeStr);
            }
        }
    }

    @Override
    public void connectionClosed() {
        log.warn("TWS connection closed");
    }

    @Override
    public void connectAck() {
        if (clientSocket.isAsyncEConnect()) {
            log.info("Acknowledging async TWS connection");
            clientSocket.startAPI();
        }
    }
    //! [connectack]

    @Override
    public void positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {
        log.debug("Position Multi: reqId={}, account={}, symbol={}, pos={}", reqId, account, contract.symbol(), pos);
    }

    @Override
    public void positionMultiEnd(int reqId) {
        log.debug("Position Multi End: reqId={}", reqId);
    }

    @Override
    public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency) {
        log.debug("Account Update Multi: reqId={}, key={}, value={}", reqId, key, value);
    }

    @Override
    public void accountUpdateMultiEnd(int reqId) {
        log.debug("Account Update Multi End: reqId={}", reqId);
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier,
                                                    Set<String> expirations, Set<Double> strikes) {
        log.debug("Security Definition Optional Parameter: reqId={}, exchange={}, tradingClass={}", reqId, exchange, tradingClass);
    }

    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        log.debug("Security Definition Optional Parameter End: reqId={}", reqId);
    }

    @Override
    public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {
        log.debug("Soft Dollar Tiers: reqId={}, tiersCount={}", reqId, tiers != null ? tiers.length : 0);
    }

    @Override
    public void familyCodes(FamilyCode[] familyCodes) {
        log.debug("Family Codes received: count={}", familyCodes != null ? familyCodes.length : 0);
    }

    @Override
    public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
        log.debug("Symbol Samples: reqId={}, count={}", reqId, contractDescriptions != null ? contractDescriptions.length : 0);
    }

    @Override
    public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {
        log.debug("Market Depth Exchanges: count={}", depthMktDataDescriptions != null ? depthMktDataDescriptions.length : 0);
    }

    @Override
    public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {
        log.info("Tick News: tickerId={}, provider={}, headline={}", tickerId, providerCode, headline);
    }

    @Override
    public void smartComponents(int reqId, Map<Integer, Map.Entry<String, Character>> theMap) {
        log.debug("Smart Components: reqId={}, size={}", reqId, theMap != null ? theMap.size() : 0);
    }

    @Override
    public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
        log.trace("Tick Req Params: tickerId={}, minTick={}, bboExchange={}", tickerId, minTick, bboExchange);
    }

    @Override
    public void newsProviders(NewsProvider[] newsProviders) {
        log.debug("News Providers: count={}", newsProviders != null ? newsProviders.length : 0);
    }

    @Override
    public void newsArticle(int requestId, int articleType, String articleText) {
        log.debug("News Article: requestId={}, type={}, textLength={}", requestId, articleType, articleText != null ? articleText.length() : 0);
    }

    @Override
    public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
        log.debug("Historical News: requestId={}, provider={}, headline={}", requestId, providerCode, headline);
    }

    @Override
    public void historicalNewsEnd(int requestId, boolean hasMore) {
        log.debug("Historical News End: requestId={}, hasMore={}", requestId, hasMore);
    }

    @Override
    public void headTimestamp(int reqId, String headTimestamp) {
        log.debug("Head Timestamp: reqId={}, timestamp={}", reqId, headTimestamp);
    }

    @Override
    public void histogramData(int reqId, List<HistogramEntry> items) {
        log.debug("Histogram Data: reqId={}, itemsCount={}", reqId, items != null ? items.size() : 0);
    }

    @Override
    public void historicalDataUpdate(int reqId, Bar bar) {
        log.trace("Historical Data Update: reqId={}, time={}, C={}", reqId, bar.time(), bar.close());
    }

    @Override
    public void rerouteMktDataReq(int reqId, int conId, String exchange) {
        log.debug("Reroute Market Data: reqId={}, conId={}, exchange={}", reqId, conId, exchange);
    }

    @Override
    public void rerouteMktDepthReq(int reqId, int conId, String exchange) {
        log.debug("Reroute Market Depth: reqId={}, conId={}, exchange={}", reqId, conId, exchange);
    }

    @Override
    public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {
        log.debug("Market Rule: marketRuleId={}, incrementsCount={}", marketRuleId, priceIncrements != null ? priceIncrements.length : 0);
    }

    @Override
    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
        log.info("PnL: reqId={}, daily={}, unrealized={}, realized={}", reqId, dailyPnL, unrealizedPnL, realizedPnL);
    }

    @Override
    public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
        log.info("PnL Single: reqId={}, pos={}, daily={}, unrealized={}, realized={}", reqId, pos, dailyPnL, unrealizedPnL, realizedPnL);
    }

    @Override
    public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done) {
        log.trace("Historical Ticks: reqId={}, count={}, done={}", reqId, ticks.size(), done);
    }

    @Override
    public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {
        log.trace("Historical Ticks Bid/Ask: reqId={}, count={}, done={}", reqId, ticks.size(), done);
    }

    @Override
    public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {
        log.trace("Historical Ticks Last: reqId={}, count={}, done={}", reqId, ticks.size(), done);
    }

    @Override
    public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast,
                                  String exchange, String specialConditions) {
        log.trace("Tick-by-Tick Last: reqId={}, price={}, size={}, exchange={}", reqId, price, size, exchange);
    }

    @Override
    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize,
                                 TickAttribBidAsk tickAttribBidAsk) {
        log.trace("Tick-by-Tick Bid/Ask: reqId={}, bid={}, ask={}", reqId, bidPrice, askPrice);
    }

    @Override
    public void tickByTickMidPoint(int reqId, long time, double midPoint) {
        log.trace("Tick-by-Tick MidPoint: reqId={}, midPoint={}", reqId, midPoint);
    }

    @Override
    public void orderBound(long permId, int clientId, int orderId) {
        orderLog.info("ORDER_BOUND | permId={} | clientId={} | orderId={}", permId, clientId, orderId);
    }

    @Override
    public void completedOrder(Contract contract, Order order, OrderState orderState) {
        orderLog.info("COMPLETED_ORDER | symbol={} | action={} | qty={} | status={}",
                contract.symbol(), order.action(), order.totalQuantity(), orderState.status());
    }

    @Override
    public void completedOrdersEnd() {
        log.debug("Completed orders request finished");
    }

    @Override
    public void replaceFAEnd(int reqId, String text) {
        log.debug("Replace FA End: reqId={}, text={}", reqId, text);
    }

    @Override
    public void wshMetaData(int reqId, String dataJson) {
        log.debug("WSH Meta Data: reqId={}, dataLength={}", reqId, dataJson != null ? dataJson.length() : 0);
    }

    @Override
    public void wshEventData(int reqId, String dataJson) {
        log.debug("WSH Event Data: reqId={}, dataLength={}", reqId, dataJson != null ? dataJson.length() : 0);
    }

    @Override
    public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, List<HistoricalSession> sessions) {
        log.debug("Historical Schedule: reqId={}, start={}, end={}, tz={}, sessions={}",
                reqId, startDateTime, endDateTime, timeZone, sessions != null ? sessions.size() : 0);
    }

    @Override
    public void userInfo(int reqId, String whiteBrandingId) {
        log.debug("User Info: reqId={}, whiteBrandingId={}", reqId, whiteBrandingId);
    }

    @Override
    public void currentTimeInMillis(long timeInMillis) {
        log.debug("Server time (millis): {}", timeInMillis);
    }

    // ---------------------------------------------- Protobuf ---------------------------------------------
    //! [orderStatus]
    @Override public void orderStatusProtoBuf(OrderStatusProto.OrderStatus orderStatusProto) { }
    //! [orderStatus]

    //! [openOrder]
    @Override public void openOrderProtoBuf(OpenOrderProto.OpenOrder openOrderProto) { }
    //! [openOrder]

    //! [openOrdersEnd]
    @Override public void openOrdersEndProtoBuf(OpenOrdersEndProto.OpenOrdersEnd openOrdersEnd) { }
    //! [openOrdersEnd]

    //! [error]
    @Override public void errorProtoBuf(ErrorMessageProto.ErrorMessage errorMessageProto) { }
    //! [error]

    //! [execDetails]
    @Override public void execDetailsProtoBuf(ExecutionDetailsProto.ExecutionDetails executionDetailsProto) { }
    //! [execDetails]

    //! [execDetailsEnd]
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEndProto.ExecutionDetailsEnd executionDetailsEndProto) { }
    //! [execDetailsEnd]
}
