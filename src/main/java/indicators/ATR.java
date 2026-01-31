package indicators;

import com.ib.client.Bar;

import java.util.ArrayList;
import java.util.List;

public final class ATR {
    // ATR = [Previous ATR * (n-1) + True Range)] / n
    private ATR() {} // Prevent instantiation

    public static double calculate(List<Bar> bars, int period) {
        List<Double> series = calculateSeries(bars, period);
        return series.get(series.size() - 1);
    }

    public static List<Double> calculateSeries(List<Bar> bars, int period) {
        validateInputs(bars, period);

        List<Double> atrValues = new ArrayList<>();

        // Step 1: Calculate all True Ranges (starting from index 1)
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            trueRanges.add(calculateTrueRange(bars.get(i), bars.get(i - 1)));
        }

        // Step 2: First ATR = Simple average of first 'period' True Ranges
        double firstATR = 0.0;
        for (int i = 0; i < period; i++) {
            firstATR += trueRanges.get(i);
        }
        firstATR /= period;
        atrValues.add(firstATR);

        // Step 3: Subsequent ATRs using Wilder's Smoothing
        // ATR[i] = (ATR[i-1] * (n-1) + TR[i]) / n
        double previousATR = firstATR;
        for (int i = period; i < trueRanges.size(); i++) {
            double currentATR = (previousATR * (period - 1) + trueRanges.get(i)) / period;
            atrValues.add(currentATR);
            previousATR = currentATR;
        }

        return atrValues;
    }

    public static double getATRMultiple(List<Bar> bars, int period, double multiplier) {
        return calculate(bars, period) * multiplier;
    }

    public static double calculateStopLoss(List<Bar> bars, int period, double entryPrice, double multiplier) {
        return entryPrice - getATRMultiple(bars, period, multiplier);
    }

    public static int calculatePositionSize(List<Bar> bars, int period, double accountRisk, double stopMultiplier) {
        double stopDistance = getATRMultiple(bars, period, stopMultiplier);
        if (stopDistance <= 0) {
            throw new IllegalStateException("Stop distance must be positive");
        }
        return (int) Math.floor(accountRisk / stopDistance);
    }

    public static double getVolatilityPercentage(List<Bar> bars, int period) {
        double atr = calculate(bars, period);
        double currentPrice = bars.get(bars.size() - 1).close();
        if (currentPrice <= 0) {
            throw new IllegalStateException("Current price must be positive");
        }
        return (atr / currentPrice) * 100;
    }

    private static void validateInputs(List<Bar> bars, int period) {
        if (bars == null) {
            throw new IllegalArgumentException("Bars list cannot be null");
        }
        if (period < 1) {
            throw new IllegalArgumentException("Period must be at least 1, got: " + period);
        }
        if (bars.size() < period + 1) {
            throw new IllegalArgumentException(
                String.format("Not enough bars for ATR calculation. Required: %d, Available: %d",
                    period + 1, bars.size())
            );
        }
    }

    private static double calculateTrueRange(Bar currentBar, Bar previousBar) {
        double highLow = currentBar.high() - currentBar.low();
        double highPrevClose = Math.abs(currentBar.high() - previousBar.close());
        double prevCloseLow = Math.abs(previousBar.close() - currentBar.low());
        return Math.max(highLow, Math.max(highPrevClose, prevCloseLow));
    }
}