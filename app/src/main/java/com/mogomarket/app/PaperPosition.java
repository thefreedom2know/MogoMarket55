package com.mogomarket.app;

public class PaperPosition {
    public String symbol;
    public double shares;
    public double averagePrice;
    public double lastPrice;

    public PaperPosition() {}

    public PaperPosition(String symbol, double shares, double averagePrice, double lastPrice) {
        this.symbol = symbol;
        this.shares = shares;
        this.averagePrice = averagePrice;
        this.lastPrice = lastPrice;
    }
}
