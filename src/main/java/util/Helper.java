package util;

import java.math.BigDecimal;

public class Helper {
    public static int countDecimals(double number) {
        if (number == 0 || number == Math.floor(number)) {
            return 0;  // Integer or zero
        }

        BigDecimal bd = BigDecimal.valueOf(number);

        // Strip trailing zeros
        bd = bd.stripTrailingZeros();

        // Get scale (number of digits to right of decimal point)
        int scale = bd.scale();

        return Math.max(0, scale);
    }}
