package com.mogomarket.app;

public class DailyQuoteMath {
    public static double percentChange(double current, double base) {
        if (Double.isNaN(current) || Double.isNaN(base) || base == 0) {
            return Double.NaN;
        }
        return ((current - base) / base) * 100.0;
    }
}
