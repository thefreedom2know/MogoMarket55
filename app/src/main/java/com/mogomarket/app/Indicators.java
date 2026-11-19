package com.mogomarket.app;

import java.util.Arrays;

/** Technical indicators on a close-price series. Undefined points are NaN. Pure logic. */
final class Indicators {

    private Indicators() { }

    private static float[] nanArray(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    static float[] sma(float[] c, int n) {
        float[] out = nanArray(c.length);
        if (n <= 0 || c.length < n) return out;
        double sum = 0;
        for (int i = 0; i < c.length; i++) {
            sum += c[i];
            if (i >= n) sum -= c[i - n];
            if (i >= n - 1) out[i] = (float) (sum / n);
        }
        return out;
    }

    /** Returns {middle, upper, lower}. */
    static float[][] bollinger(float[] c, int n, double k) {
        float[] mid = sma(c, n);
        float[] up = nanArray(c.length);
        float[] low = nanArray(c.length);
        for (int i = n - 1; i < c.length; i++) {
            if (Float.isNaN(mid[i])) continue;
            double var = 0;
            for (int j = i - n + 1; j <= i; j++) {
                double d = c[j] - mid[i];
                var += d * d;
            }
            double sd = Math.sqrt(var / n);
            up[i] = (float) (mid[i] + k * sd);
            low[i] = (float) (mid[i] - k * sd);
        }
        return new float[][]{mid, up, low};
    }

    /** EMA over a series that may start with NaNs; seeded with the SMA of the first n values. */
    static float[] ema(float[] v, int n) {
        float[] out = nanArray(v.length);
        int first = 0;
        while (first < v.length && Float.isNaN(v[first])) first++;
        if (v.length - first < n) return out;
        double sum = 0;
        for (int i = first; i < first + n; i++) sum += v[i];
        double e = sum / n;
        out[first + n - 1] = (float) e;
        double k = 2.0 / (n + 1);
        for (int i = first + n; i < v.length; i++) {
            e = v[i] * k + e * (1 - k);
            out[i] = (float) e;
        }
        return out;
    }

    /** Wilder's RSI. */
    static float[] rsi(float[] c, int n) {
        float[] out = nanArray(c.length);
        if (c.length <= n) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            if (d >= 0) gain += d; else loss -= d;
        }
        gain /= n;
        loss /= n;
        out[n] = rsiValue(gain, loss);
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
            out[i] = rsiValue(gain, loss);
        }
        return out;
    }

    private static float rsiValue(double gain, double loss) {
        if (loss == 0) return gain == 0 ? 50f : 100f;
        return (float) (100.0 - 100.0 / (1.0 + gain / loss));
    }

    /** Returns {macd, signal, histogram}. */
    static float[][] macd(float[] c, int fast, int slow, int signal) {
        float[] f = ema(c, fast);
        float[] s = ema(c, slow);
        float[] line = nanArray(c.length);
        for (int i = 0; i < c.length; i++) {
            if (!Float.isNaN(f[i]) && !Float.isNaN(s[i])) line[i] = f[i] - s[i];
        }
        float[] sig = ema(line, signal);
        float[] hist = nanArray(c.length);
        for (int i = 0; i < c.length; i++) {
            if (!Float.isNaN(line[i]) && !Float.isNaN(sig[i])) hist[i] = line[i] - sig[i];
        }
        return new float[][]{line, sig, hist};
    }
}
