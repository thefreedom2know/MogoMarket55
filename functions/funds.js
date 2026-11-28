"use strict";

/**
 * Israeli long-term savings (קופות גמל, קרנות השתלמות, פנסיה, ביטוח מנהלים).
 *
 * Data comes from the Capital Market Authority's open data on data.gov.il:
 *   Gemel-Net   (gemel, hishtalmut, gemel lehashkaa, child savings)
 *   Pension-Net (pension funds)
 *   Bituah-Net  (insurance policies with savings)
 * Each dataset holds one row per fund track per month (REPORT_PERIOD = YYYYMM).
 *
 * The app keeps the user's own funds in users/<uid>/funds/<key> (fund number, balance, fees...).
 * The server only writes the `market` child of each fund (admin access, so no rule changes),
 * and keeps `fund-watch/<uid>` so the monthly job knows whose funds to refresh.
 */

const CKAN = "https://data.gov.il/api/3/action/datastore_search";
const UA = "Mozilla/5.0 (compatible; MogoMarket funds updater)";

/** Newest resource ("2024 until today") of each dataset. */
const RESOURCES = {
  gemel: "a30dcbea-a1d2-482c-ae29-8f781f5025fb",
  pension: "6d47d6b5-cb08-488b-b333-f1e717b1e1bd",
  bituach: "c6c62cc7-fe02-4b18-8f3e-813abfbb4647",
};
const SOURCE_ORDER = ["gemel", "pension", "bituach"];

const INDEXES = {
  ta125: "^TA125.TA",
  sp500: "^GSPC",
};

function num(v) {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(String(v).replace(/[,%\s]/g, ""));
  return Number.isFinite(n) ? n : null;
}

function str(v) {
  if (v === null || v === undefined) return "";
  return String(v).trim();
}

/** One data.gov.il row in the shape the app reads. Missing numbers stay null. */
function normRecord(r, source) {
  return {
    source,
    fundId: num(r.FUND_ID),
    name: str(r.FUND_NAME),
    company: str(r.MANAGING_CORPORATION),
    classification: str(r.FUND_CLASSIFICATION),
    specialization: str(r.SPECIALIZATION),
    subSpecialization: str(r.SUB_SPECIALIZATION),
    targetPopulation: str(r.TARGET_POPULATION),
    period: num(r.REPORT_PERIOD),
    monthlyYield: num(r.MONTHLY_YIELD),
    ytdYield: num(r.YEAR_TO_DATE_YIELD),
    yield3y: num(r.YIELD_TRAILING_3_YRS),
    yield5y: num(r.YIELD_TRAILING_5_YRS),
    avgAnnual3y: num(r.AVG_ANNUAL_YIELD_TRAILING_3YRS),
    avgAnnual5y: num(r.AVG_ANNUAL_YIELD_TRAILING_5YRS),
    fee: num(r.AVG_ANNUAL_MANAGEMENT_FEE),
    depositFee: num(r.AVG_DEPOSIT_FEE),
    totalAssets: num(r.TOTAL_ASSETS),
    stockExposure: num(r.STOCK_MARKET_EXPOSURE),
    foreignExposure: num(r.FOREIGN_EXPOSURE),
    fxExposure: num(r.FOREIGN_CURRENCY_EXPOSURE),
    sharpe: num(r.SHARPE_RATIO),
    stdDev: num(r.STANDARD_DEVIATION),
  };
}

/** Removes null fields: the Realtime Database refuses undefined and stores null as "delete". */
function compact(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === null || v === undefined || v === "") continue;
    out[k] = v;
  }
  return out;
}

async function ckan(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    q.set(k, typeof v === "object" ? JSON.stringify(v) : String(v));
  }
  const res = await fetch(`${CKAN}?${q.toString()}`, {
    headers: { "User-Agent": UA, Accept: "application/json" },
  });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch (e) {
    throw new Error(`data.gov.il answered ${res.status} with a non-JSON page`);
  }
  if (!body.success) throw new Error("data.gov.il request failed");
  return body.result;
}

/** All months of one fund in one dataset, newest first. */
async function fetchFundHistory(fundId, source) {
  const result = await ckan({
    resource_id: RESOURCES[source],
    filters: { FUND_ID: Number(fundId) },
    sort: "REPORT_PERIOD desc",
    limit: 120,
  });
  return (result.records || []).map((r) => normRecord(r, source)).filter((r) => r.period);
}

async function latestPeriod(source) {
  const result = await ckan({
    resource_id: RESOURCES[source],
    fields: "REPORT_PERIOD",
    sort: "REPORT_PERIOD desc",
    limit: 1,
  });
  const r = (result.records || [])[0];
  return r ? num(r.REPORT_PERIOD) : null;
}

/** Every fund track of one dataset for one month (used for the peer comparison). */
async function fetchPeriod(source, period) {
  const rows = [];
  for (let offset = 0; offset < 20000; offset += 5000) {
    const result = await ckan({
      resource_id: RESOURCES[source],
      filters: { REPORT_PERIOD: Number(period) },
      limit: 5000,
      offset,
    });
    const got = result.records || [];
    for (const r of got) rows.push(normRecord(r, source));
    if (got.length < 5000) break;
  }
  return rows;
}

/** Text search in the newest month of the gemel and pension datasets. */
async function searchFunds(query) {
  const out = [];
  for (const source of ["gemel", "pension"]) {
    try {
      const period = await latestPeriod(source);
      if (!period) continue;
      const result = await ckan({
        resource_id: RESOURCES[source],
        q: query,
        filters: { REPORT_PERIOD: period },
        limit: 25,
      });
      for (const r of result.records || []) out.push(normRecord(r, source));
    } catch (e) {
      // One dataset failing should not hide the other one's matches.
    }
  }
  return out.slice(0, 40);
}

function average(values) {
  const v = values.filter((x) => x !== null && Number.isFinite(x));
  if (v.length === 0) return null;
  return v.reduce((a, b) => a + b, 0) / v.length;
}

function round(v, digits = 2) {
  if (v === null || v === undefined || !Number.isFinite(v)) return null;
  const f = Math.pow(10, digits);
  return Math.round(v * f) / f;
}

/**
 * Funds in the same track as `fund` (same classification, specialization and sub-specialization;
 * when fewer than 3 share all three, the sub-specialization is dropped) and how `fund` ranks.
 */
function peerStats(fund, rows) {
  const same = (a, b) => str(a) === str(b);
  let group = rows.filter(
    (r) =>
      same(r.classification, fund.classification) &&
      same(r.specialization, fund.specialization) &&
      same(r.subSpecialization, fund.subSpecialization)
  );
  let level = "track";
  if (group.length < 3) {
    group = rows.filter(
      (r) => same(r.classification, fund.classification) && same(r.specialization, fund.specialization)
    );
    level = "specialization";
  }
  if (group.length === 0) return null;

  const ranked = group
    .filter((r) => r.avgAnnual3y !== null)
    .sort((a, b) => b.avgAnnual3y - a.avgAnnual3y);
  const rankIndex = ranked.findIndex((r) => r.fundId === fund.fundId);
  const fees = group.map((r) => r.fee).filter((x) => x !== null).sort((a, b) => a - b);

  return compact({
    level,
    count: group.length,
    avgFee: round(average(group.map((r) => r.fee)), 3),
    medianFee: fees.length ? round(fees[Math.floor(fees.length / 2)], 3) : null,
    avgDepositFee: round(average(group.map((r) => r.depositFee)), 3),
    avgMonthly: round(average(group.map((r) => r.monthlyYield))),
    avgYtd: round(average(group.map((r) => r.ytdYield))),
    avgAnnual3y: round(average(group.map((r) => r.avgAnnual3y))),
    avgAnnual5y: round(average(group.map((r) => r.avgAnnual5y))),
    rank3y: rankIndex >= 0 ? rankIndex + 1 : null,
    ranked3y: ranked.length || null,
    top: ranked.slice(0, 3).map((r) =>
      compact({
        fundId: r.fundId,
        name: r.name,
        company: r.company,
        avgAnnual3y: r.avgAnnual3y,
        fee: r.fee,
      })
    ),
  });
}

function prevPeriod(p) {
  const y = Math.floor(p / 100);
  const m = p % 100;
  return m === 1 ? (y - 1) * 100 + 12 : y * 100 + (m - 1);
}

function addMonths(p, n) {
  let y = Math.floor(p / 100);
  let m = (p % 100) + n;
  while (m > 12) { m -= 12; y += 1; }
  while (m < 1) { m += 12; y -= 1; }
  return y * 100 + m;
}

/** Compounded return (%) of the monthly yields from `fromPeriod` to `toPeriod` inclusive, or null if a month is missing. */
function compound(history, fromPeriod, toPeriod) {
  const byPeriod = new Map(history.map((h) => [h.period, h.monthlyYield]));
  let factor = 1;
  for (let p = fromPeriod; p <= toPeriod; p = addMonths(p, 1)) {
    const y = byPeriod.get(p);
    if (y === null || y === undefined) return null;
    factor *= 1 + y / 100;
  }
  return round((factor - 1) * 100);
}

/**
 * Estimated value today: the balance the user entered (as of `balancePeriod`) grown by each later
 * month's gross return, minus 1/12 of the yearly fee from balance, plus the monthly deposit.
 * Gemel-Net returns are before fees, so the fee is taken off here.
 */
function estimateValue({ balance, balancePeriod, monthlyDeposit, feeBalance }, history) {
  const b = num(balance);
  if (b === null) return null;
  const latest = history.length ? Math.max(...history.map((h) => h.period)) : null;
  const start = num(balancePeriod);
  if (!latest || !start || start >= latest) {
    return { value: round(b), period: start || latest || null, months: 0 };
  }
  const byPeriod = new Map(history.map((h) => [h.period, h.monthlyYield]));
  const fee = (num(feeBalance) || 0) / 100 / 12;
  const deposit = num(monthlyDeposit) || 0;
  let v = b;
  let months = 0;
  for (let p = addMonths(start, 1); p <= latest; p = addMonths(p, 1)) {
    const y = byPeriod.get(p);
    if (y !== null && y !== undefined) v *= 1 + y / 100;
    v -= v * fee;
    v += deposit;
    months += 1;
  }
  return { value: round(v), period: latest, months };
}

/** Month-end closes of a Yahoo index, keyed by YYYYMM. */
async function fetchIndexCloses(symbol) {
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=10y&interval=1mo`;
  const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
  if (!res.ok) throw new Error(`Yahoo ${res.status}`);
  const body = await res.json();
  const item = body && body.chart && body.chart.result && body.chart.result[0];
  if (!item) throw new Error("Yahoo answered without data");
  const offset = (item.meta && item.meta.gmtoffset) || 0;
  const ts = item.timestamp || [];
  const closes = (item.indicators && item.indicators.quote && item.indicators.quote[0].close) || [];
  const out = new Map();
  for (let i = 0; i < ts.length; i++) {
    const c = closes[i];
    if (c === null || c === undefined) continue;
    const d = new Date((ts[i] + offset) * 1000);
    out.set(d.getUTCFullYear() * 100 + d.getUTCMonth() + 1, c);
  }
  return out;
}

/** Index return (%) from the end of `beforePeriod` to the end of `endPeriod`. */
function indexReturn(closes, beforePeriod, endPeriod) {
  const a = closes.get(beforePeriod);
  const b = closes.get(endPeriod);
  if (!a || !b) return null;
  return round((b / a - 1) * 100);
}

/** Per-run cache so that one scheduled run fetches each fund and each month only once. */
function createContext() {
  const histories = new Map();
  const periods = new Map();
  const indexes = new Map();
  return {
    async history(fundId, preferredSource) {
      const key = String(fundId);
      if (histories.has(key)) return histories.get(key);
      const order = preferredSource && RESOURCES[preferredSource]
        ? [preferredSource, ...SOURCE_ORDER.filter((s) => s !== preferredSource)]
        : SOURCE_ORDER;
      let found = [];
      let lastError = null;
      for (const source of order) {
        try {
          found = await fetchFundHistory(fundId, source);
        } catch (e) {
          lastError = e;
          continue;
        }
        if (found.length) break;
      }
      if (!found.length && lastError) throw lastError;
      histories.set(key, found);
      return found;
    },
    async periodRows(source, period) {
      const key = `${source}:${period}`;
      if (!periods.has(key)) periods.set(key, fetchPeriod(source, period).catch(() => []));
      return periods.get(key);
    },
    async index(name) {
      if (!indexes.has(name)) indexes.set(name, fetchIndexCloses(INDEXES[name]).catch(() => new Map()));
      return indexes.get(name);
    },
  };
}

/** Everything the app shows for one fund, built from its history. */
async function buildMarket(fund, ctx) {
  const history = await ctx.history(fund.fundId, fund.source);
  if (!history.length) {
    return { notFound: true, checkedAt: Date.now() };
  }
  const latest = history[0];
  const end = latest.period;

  const rows = await ctx.periodRows(latest.source, end);
  const peers = rows.length ? peerStats(latest, rows) : null;

  const start12 = addMonths(end, -11);
  const return12m = compound(history, start12, end);

  const index = {};
  for (const name of Object.keys(INDEXES)) {
    const closes = await ctx.index(name);
    index[name] = compact({
      r12m: indexReturn(closes, prevPeriod(start12), end),
      ytd: indexReturn(closes, Math.floor(end / 100) * 100 - 100 + 12, end),
    });
  }

  const hist = {};
  for (const h of history.slice(0, 36)) {
    if (h.monthlyYield !== null) hist[String(h.period)] = h.monthlyYield;
  }

  const feeBalance = num(fund.feeBalance) !== null ? num(fund.feeBalance) : latest.fee;
  const estimate = estimateValue(
    {
      balance: fund.balance,
      balancePeriod: fund.balancePeriod,
      monthlyDeposit: fund.monthlyDeposit,
      feeBalance,
    },
    history
  );

  return compact({
    ...compact(latest),
    return12m,
    peers,
    index,
    history: hist,
    estimatedValue: estimate ? estimate.value : null,
    estimatedPeriod: estimate ? estimate.period : null,
    updatedAt: Date.now(),
  });
}

/** Refreshes the `market` block of every fund of one user. */
async function refreshUser(db, uid, ctx) {
  const snap = await db.ref(`users/${uid}/funds`).get();
  const funds = snap.val() || {};
  const updates = {};
  let updated = 0;
  let notFound = 0;
  let withId = 0;
  for (const [key, fund] of Object.entries(funds)) {
    if (!fund || !num(fund.fundId)) continue;
    withId += 1;
    try {
      const market = await buildMarket(fund, ctx);
      updates[`${key}/market`] = market;
      if (market.notFound) notFound += 1;
      else updated += 1;
    } catch (e) {
      updates[`${key}/market/error`] = String(e.message || e).slice(0, 200);
      updates[`${key}/market/checkedAt`] = Date.now();
    }
  }
  if (Object.keys(updates).length) await db.ref(`users/${uid}/funds`).update(updates);
  if (withId > 0) await db.ref(`fund-watch/${uid}`).set({ funds: withId, at: Date.now() });
  else await db.ref(`fund-watch/${uid}`).remove();
  return { updated, notFound, funds: withId };
}

/** Newest month published in each dataset, e.g. { gemel: 202610, pension: 202610, bituach: 202609 }. */
async function latestPeriods() {
  const out = {};
  for (const source of SOURCE_ORDER) {
    try {
      out[source] = await latestPeriod(source);
    } catch (e) {
      out[source] = null;
    }
  }
  return out;
}

module.exports = {
  RESOURCES,
  latestPeriods,
  normRecord,
  peerStats,
  compound,
  estimateValue,
  indexReturn,
  addMonths,
  prevPeriod,
  createContext,
  buildMarket,
  refreshUser,
  searchFunds,
  fetchFundHistory,
};
