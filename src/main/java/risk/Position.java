package risk;

import com.ib.client.*;
import ibkr.IBKRConnection;
import ibkr.model.AccountSummaryOutput;
import ibkr.model.PositionOutput;
import indicators.ATR;
import util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class Position {

    private static final Logger log = LoggerFactory.getLogger(Position.class);
    private static final Logger orderLog = LoggerFactory.getLogger("ORDER_AUDIT");

    // this class needs to know account balance/buying

    // also differentitate strategies type
    IBKRConnection ibkrConnection;
    private final double RISK_MULTIPLE = 1.5;

    public Position(IBKRConnection ibkrConnection) {
        this.ibkrConnection = ibkrConnection;
    }

    //    * NetLiquidation = Total account value (cash + positions)
//    * AvailableFunds = Funds available for trading
    // need availblefunds to see if can trade enuf money and use netliqudation to do position sizing
    public void calculateEntryLowFloatMomentum(Contract contract, Bar firstBullishBar, List<Bar> historicalBars, String strategyName) throws ExecutionException, InterruptedException, TimeoutException {
        // for shares - we need use whole numbers as TWS API dont allow for fractional shares
        ContractDetails contractDetails = ibkrConnection.reqContractDetails(contract);

        // Debug: Check what minTick IBKR returns
        double minTick = contractDetails.minTick();
        log.info("[{}] Contract minTick from IBKR: {}, countDecimals: {}",
                contract.symbol(), minTick, Helper.countDecimals(minTick));

        // cash
        List<AccountSummaryOutput> accountSummaryOutputList = ibkrConnection.reqAccountSummary("NetLiquidation,AvailableFunds,BuyingPower,HighestSeverity");
        double netLiquidation = AccountSummaryOutput.getValueWithTag(accountSummaryOutputList, "NetLiquidation");
        double availableFunds = AccountSummaryOutput.getValueWithTag(accountSummaryOutputList, "AvailableFunds");

        //TODO: Hardcode with my own mintick
        double entryPrice = roundToTick((firstBullishBar.high() + Constants.STANDARD_TICK_SIZE * 3)
                , Constants.STANDARD_TICK_SIZE
                , false);


        double vwap = firstBullishBar.wap().value().doubleValue();
        double atr = ATR.calculate(historicalBars, 10);

        //TODO: check this ATR multiplier
        double rawStopLossPrice  = vwap - (1 * atr);
        //TODO: Hardcode with my own mintick
        double stopLossPrice = roundToTick(rawStopLossPrice, Constants.STANDARD_TICK_SIZE, true);

        double riskAmount = calculateRiskAmount(netLiquidation, Constants.RISK_PER_TRADE);

        double positionSize = calculatePositionSize(entryPrice, stopLossPrice, riskAmount, true);

        //TODO: Hardcode with my own mintick
        double takeProfitPrice = calculateTakeProfitPrice(entryPrice, stopLossPrice, RISK_MULTIPLE, Constants.STANDARD_TICK_SIZE, true);


        Order parentOrder = new Order();
        parentOrder.action("BUY");
        parentOrder.totalQuantity(Decimal.get(positionSize));
        parentOrder.orderType("Limit");  // or "LMT" with lmtPrice
        parentOrder.lmtPrice(entryPrice);
        parentOrder.transmit(false);   // DON'T TRANSMIT YET
        parentOrder.orderRef(strategyName);


        // 2. TAKE PROFIT - Child Limit Sell
        Order takeProfitOrder = new Order();
        takeProfitOrder.action("SELL");
        takeProfitOrder.totalQuantity(Decimal.get(positionSize));
        takeProfitOrder.orderType("LMT");
        takeProfitOrder.lmtPrice(takeProfitPrice);  // Your profit target
        takeProfitOrder.transmit(false);  // DON'T TRANSMIT YET
        takeProfitOrder.orderRef(strategyName);


        // 3. STOP LOSS - Child Stop Sell
        Order stopLossOrder = new Order();
        stopLossOrder.action("SELL");
        stopLossOrder.totalQuantity(Decimal.get(positionSize));
        stopLossOrder.orderType("STP");  // or "STP LMT"
        stopLossOrder.auxPrice(stopLossPrice);  // Your stop price
        stopLossOrder.transmit(true);  // NOW TRANSMIT ALL THREE
        stopLossOrder.orderRef(strategyName);

        ibkrConnection.placeBracketOrders(contract, parentOrder, takeProfitOrder, stopLossOrder);
    }


    /**
     * Bull Flag Breakout entry: BUY LIMIT at flagHigh + 3 ticks,
     * stop at flagLow, target at 2.0× R:R.
     *
     * @param contract       the stock to trade
     * @param breakoutBar    the current (breakout) bar — used for logging only
     * @param flagHigh       highest high of the flag consolidation bars
     * @param flagLow        lowest low of the flag consolidation bars
     * @param historicalBars full bar history (used for ATR sanity reference)
     * @param strategyName   written into orderRef for all three bracket legs
     */
    public void calculateEntryBullFlag(Contract contract, Bar breakoutBar,
            double flagHigh, double flagLow, List<Bar> historicalBars, String strategyName)
            throws ExecutionException, InterruptedException, TimeoutException {

        String symbol = contract.symbol();
        log.info("[{}] calculateEntryBullFlag called — flagHigh={}, flagLow={}, breakoutBar=[close={}, open={}, vwap={}]",
                symbol, flagHigh, flagLow, breakoutBar.close(), breakoutBar.open(), breakoutBar.wap());

        ContractDetails contractDetails = ibkrConnection.reqContractDetails(contract);
        log.info("[{}] Contract minTick: {}", symbol, contractDetails.minTick());

        List<AccountSummaryOutput> accountSummary = ibkrConnection.reqAccountSummary(
                "NetLiquidation,AvailableFunds,BuyingPower,HighestSeverity");
        double netLiquidation = AccountSummaryOutput.getValueWithTag(accountSummary, "NetLiquidation");
        double availableFunds = AccountSummaryOutput.getValueWithTag(accountSummary, "AvailableFunds");
        log.info("[{}] Account — netLiquidation={}, availableFunds={}", symbol, netLiquidation, availableFunds);

        // Entry: just above flag high (round UP to next tick)
        double entryPrice = roundToTick(flagHigh + Constants.STANDARD_TICK_SIZE * 3,
                Constants.STANDARD_TICK_SIZE, false);

        // Stop: flag low (round DOWN to protect the stop)
        double stopLossPrice = roundToTick(flagLow, Constants.STANDARD_TICK_SIZE, true);

        // Defensive guard: stop must be below entry
        if (stopLossPrice >= entryPrice) {
            log.warn("[{}] SKIP: flagLow ({}) >= entryPrice ({}) — invalid bracket", symbol, stopLossPrice, entryPrice);
            return;
        }

        // Defensive guard: stop width
        double riskWidth = (entryPrice - stopLossPrice) / entryPrice;
        if (riskWidth > 0.03) {
            log.warn("[{}] SKIP: risk too wide ({:.2f}%) — flagHigh={}, flagLow={}, entry={}, stop={}",
                    symbol, String.format("%.2f", riskWidth * 100), flagHigh, flagLow, entryPrice, stopLossPrice);
            return;
        }

        double riskAmount   = calculateRiskAmount(netLiquidation, Constants.RISK_PER_TRADE);
        double positionSize = calculatePositionSize(entryPrice, stopLossPrice, riskAmount, true);

        if (positionSize < 1) {
            log.warn("[{}] SKIP: position size rounds to 0 (riskAmount={}, entry={}, stop={})",
                    symbol, riskAmount, entryPrice, stopLossPrice);
            return;
        }

        double takeProfitPrice = calculateTakeProfitPrice(entryPrice, stopLossPrice, 2.0,
                Constants.STANDARD_TICK_SIZE, true);

        orderLog.info("[{}] BullFlag ORDER — entry={}, stop={}, takeProfit={}, size={}, R:R=2.0, riskWidth={:.2f}%, strategy={}",
                symbol, entryPrice, stopLossPrice, takeProfitPrice, positionSize,
                String.format("%.2f", riskWidth * 100), strategyName);

        // --- Parent BUY LIMIT ---
        Order parentOrder = new Order();
        parentOrder.action("BUY");
        parentOrder.totalQuantity(Decimal.get(positionSize));
        parentOrder.orderType("LMT");
        parentOrder.lmtPrice(entryPrice);
        parentOrder.transmit(false);
        parentOrder.orderRef(strategyName);

        // --- Take Profit SELL LIMIT ---
        Order takeProfitOrder = new Order();
        takeProfitOrder.action("SELL");
        takeProfitOrder.totalQuantity(Decimal.get(positionSize));
        takeProfitOrder.orderType("LMT");
        takeProfitOrder.lmtPrice(takeProfitPrice);
        takeProfitOrder.transmit(false);
        takeProfitOrder.orderRef(strategyName);

        // --- Stop Loss SELL STOP ---
        Order stopLossOrder = new Order();
        stopLossOrder.action("SELL");
        stopLossOrder.totalQuantity(Decimal.get(positionSize));
        stopLossOrder.orderType("STP");
        stopLossOrder.auxPrice(stopLossPrice);
        stopLossOrder.transmit(true);
        stopLossOrder.orderRef(strategyName);

        log.info("[{}] Placing bracket order — parent BUY LMT @ {}, TP SELL LMT @ {}, SL SELL STP @ {}, qty={}",
                symbol, entryPrice, takeProfitPrice, stopLossPrice, positionSize);
        ibkrConnection.placeBracketOrders(contract, parentOrder, takeProfitOrder, stopLossOrder);
        log.info("[{}] Bracket order placed successfully", symbol);
    }

    public double calculateRiskAmount(double netLiqudation, double riskPerTrade) {
        // Risk Amount = Net Liquidation  * Risk Per Trade(%)
        return netLiqudation * riskPerTrade;
    }

    public double calculatePositionSize(double entryPrice, double stopLossPrice, double riskAmount, boolean roundDown) {
        // Distance to Stop Loss = Entry Price - Stop Loss Price
        // Position Size (in shares) = Risk Amount / Distance to Stop Loss

        if(stopLossPrice >= entryPrice) {
            throw new IllegalArgumentException(
               String.format("Invalid stop loss: Stop loss price ($%.2f) must be " +
                       "below entry price ($%.2f) for long positions", stopLossPrice, entryPrice));
        }
        double distanceToStopLoss = entryPrice - stopLossPrice;
        double positionSize = riskAmount / distanceToStopLoss;

        // round to nearest whole number as IBKR dont allow for fractional shares
        if (roundDown) {
            return Math.floor(positionSize);
        }else{
            return Math.ceil(positionSize);
        }
    }

    double calculateTakeProfitPrice(double entryPrice, double stopLossPrice, double riskMultiple, double minTick, boolean roundDown) {
        double distanceToStopLoss = entryPrice - stopLossPrice;
        double rawTakeProfitPrice = entryPrice + (distanceToStopLoss * riskMultiple);
        return roundToTick(rawTakeProfitPrice, minTick, roundDown);
    }

    public static double roundToTick(double price, double minTick, boolean roundDown) {
        BigDecimal priceBD = BigDecimal.valueOf(price);
        int ticks = Helper.countDecimals(minTick);
        if (roundDown) {
            return priceBD.setScale(ticks, RoundingMode.FLOOR).doubleValue();
        } else {
            return priceBD.setScale(ticks, RoundingMode.CEILING).doubleValue();
        }
    }

    public void closeAllPositions(List<PositionOutput> positions) {
        for (PositionOutput position: positions) {
            Decimal pos = position.getPos();

            if(pos.isZero()) {
                // TODO: WILL THIS EVER even happen, check logs if not remove?
                log.info("{} position size is 0", position.getContract().symbol());
                continue;
            }

            Contract contract = position.getContract();
            Order order = new Order();

            if(pos.longValue() > 0){
                order.action("SELL");
                order.totalQuantity(pos);
            } else{
                order.action("BUY");
                order.totalQuantity(pos.negate());
            }

            order.orderType("MKT");
            order.tif("GTC");

            ibkrConnection.placeOrder(contract, order);
        }

    }

}
