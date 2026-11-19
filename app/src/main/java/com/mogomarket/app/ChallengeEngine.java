package com.mogomarket.app;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Simulator challenges: definitions, period keys and live progress. Pure logic, no UI. */
final class ChallengeEngine {

    static final int IN_PROGRESS = 0;
    static final int DONE = 1;
    static final int FAILED = 2;

    static final class Status {
        final String id;
        final String title;
        final String description;
        final String periodKey;   // e.g. M2026-11 or W2026-47
        final String detail;      // live progress text
        final double progress;    // 0..1
        final int state;

        Status(String id, String title, String description, String periodKey, String detail,
               double progress, int state) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.periodKey = periodKey;
            this.detail = detail;
            this.progress = Math.max(0, Math.min(1, progress));
            this.state = state;
        }

        /** Key under which a completed challenge is remembered. */
        String achievementKey() {
            return id + "_" + periodKey;
        }
    }

    /** Live numbers the challenges are judged on (NaN = not known yet). */
    static final class Inputs {
        double weekPct = Double.NaN;
        double monthPct = Double.NaN;
        double spxMonthPct = Double.NaN;
        int monthClosedTrades;
        int monthWins;
        double monthMaxDrawdownPct;
    }

    private ChallengeEngine() { }

    static String monthKey(long ts) {
        LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate();
        return String.format(Locale.US, "M%04d-%02d", d.getYear(), d.getMonthValue());
    }

    static String weekKey(long ts) {
        LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate();
        return String.format(Locale.US, "W%04d-%02d", d.get(IsoFields.WEEK_BASED_YEAR),
                d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    static long monthStart(long ts) {
        LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
                .withDayOfMonth(1);
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static long weekStart(long ts) {
        LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
                .with(DayOfWeek.MONDAY);
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "--" : String.format(Locale.US, "%+.2f%%", v);
    }

    static List<Status> evaluate(Inputs in, long now) {
        List<Status> list = new ArrayList<>();
        String wk = weekKey(now);
        String mk = monthKey(now);

        list.add(returnChallenge("week2", "Weekly sprint", "Grow the account by 2% this week",
                wk, in.weekPct, 2.0));
        list.add(returnChallenge("month10", "Monthly +10%", "Grow the account by 10% this month",
                mk, in.monthPct, 10.0));

        // beat the S&P 500
        {
            boolean known = !Double.isNaN(in.monthPct) && !Double.isNaN(in.spxMonthPct);
            boolean traded = in.monthClosedTrades >= 1;
            boolean done = known && traded && in.monthPct > in.spxMonthPct;
            String detail = known ? "You " + pct(in.monthPct) + "  vs  S&P 500 "
                    + pct(in.spxMonthPct) + (traded ? "" : "  (close at least one trade)")
                    : "Waiting for prices...";
            list.add(new Status("beatspx", "Beat the market",
                    "Finish the month ahead of the S&P 500 (and close at least one trade)",
                    mk, detail, done ? 1 : (known && in.monthPct > in.spxMonthPct ? 0.8 : 0.3),
                    done ? DONE : IN_PROGRESS));
        }

        // 10 trades, 60% win rate
        {
            double winRate = in.monthClosedTrades == 0 ? 0
                    : in.monthWins * 100.0 / in.monthClosedTrades;
            boolean done = in.monthClosedTrades >= 10 && winRate >= 60;
            double progress = Math.min(1.0, in.monthClosedTrades / 10.0)
                    * (in.monthClosedTrades == 0 ? 0 : Math.min(1.0, winRate / 60.0));
            list.add(new Status("sharp", "Sharpshooter",
                    "Close 10 trades this month with a win rate of at least 60%", mk,
                    String.format(Locale.US, "%d / 10 trades  ·  win rate %.0f%%",
                            in.monthClosedTrades, winRate), progress,
                    done ? DONE : IN_PROGRESS));
        }

        // protect capital
        {
            boolean breached = in.monthMaxDrawdownPct >= 5.0;
            boolean done = !breached && in.monthClosedTrades >= 5;
            list.add(new Status("guard", "Capital guardian",
                    "Close 5 trades this month without the account ever dropping 5% from its peak",
                    mk, String.format(Locale.US, "%d / 5 trades  ·  max drawdown %.2f%%",
                            in.monthClosedTrades, in.monthMaxDrawdownPct),
                    Math.min(1.0, in.monthClosedTrades / 5.0),
                    breached ? FAILED : (done ? DONE : IN_PROGRESS)));
        }
        return list;
    }

    private static Status returnChallenge(String id, String title, String desc, String period,
                                          double actualPct, double targetPct) {
        boolean known = !Double.isNaN(actualPct);
        boolean done = known && actualPct >= targetPct;
        return new Status(id, title, desc, period,
                known ? String.format(Locale.US, "%s of +%.0f%% target", pct(actualPct), targetPct)
                        : "Waiting for prices...",
                known ? actualPct / targetPct : 0, done ? DONE : IN_PROGRESS);
    }
}
