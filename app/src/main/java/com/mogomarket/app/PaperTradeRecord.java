package com.mogomarket.app;

public class PaperTradeRecord {
    public String symbol;
    public String action;
    public String side;
    public double shares;
    public double price;
    public double notional;
    public double realizedPnl;
    public long executedAt;

    public PaperTradeRecord() {}

    public PaperTradeRecord(String symbol, String action, double shares, double price, double notional, double realizedPnl, long executedAt) {
        this.symbol = symbol;
        this.action = action;
        this.side = action;
        this.shares = shares;
        this.price = price;
        this.notional = notional;
        this.realizedPnl = realizedPnl;
        this.executedAt = executedAt;
    }
}
