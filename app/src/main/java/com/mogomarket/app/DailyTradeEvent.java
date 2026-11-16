package com.mogomarket.app;

public class DailyTradeEvent {
    public String closedTradeId;
    public String symbol;
    public String action;
    public double shares;
    public double buyPrice;
    public double sellPrice;
    public double tradeAmount;
    public double profitLoss;
    public double price;
    public long timestamp;
    public long closedAt;
    public double pnl;

    public DailyTradeEvent() {}
}
