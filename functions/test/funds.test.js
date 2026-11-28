"use strict";
const test = require("node:test");
const assert = require("node:assert");
const f = require("../funds");

const hist = [
  { period: 202603, monthlyYield: 1.0 },
  { period: 202602, monthlyYield: -2.0 },
  { period: 202601, monthlyYield: 3.0 },
  { period: 202512, monthlyYield: 0.5 },
];

test("month arithmetic crosses years", () => {
  assert.strictEqual(f.addMonths(202512, 1), 202601);
  assert.strictEqual(f.addMonths(202601, -1), 202512);
  assert.strictEqual(f.addMonths(202603, -11), 202504);
  assert.strictEqual(f.prevPeriod(202601), 202512);
});

test("compound return needs every month", () => {
  assert.strictEqual(f.compound(hist, 202601, 202603), Math.round((1.03 * 0.98 * 1.01 - 1) * 10000) / 100);
  assert.strictEqual(f.compound(hist, 202510, 202603), null);
});

test("estimate grows the balance after its month, net of fee, plus deposits", () => {
  const e = f.estimateValue({ balance: 100000, balancePeriod: 202601, feeBalance: 1.2, monthlyDeposit: 1000 }, hist);
  let v = 100000;
  for (const y of [-2.0, 1.0]) { v *= 1 + y / 100; v -= v * 0.001; v += 1000; }
  assert.strictEqual(e.value, Math.round(v * 100) / 100);
  assert.strictEqual(e.period, 202603);
  assert.strictEqual(e.months, 2);
});

test("estimate keeps the balance when it is already up to date", () => {
  const e = f.estimateValue({ balance: "50,000", balancePeriod: 202605 }, hist);
  assert.strictEqual(e.value, 50000);
  assert.strictEqual(f.estimateValue({ balance: null }, hist), null);
});

test("normRecord reads data.gov.il rows", () => {
  const r = f.normRecord({ FUND_ID: "1234", FUND_NAME: " קרן ", REPORT_PERIOD: 202609, MONTHLY_YIELD: "1.23", AVG_ANNUAL_MANAGEMENT_FEE: null }, "gemel");
  assert.strictEqual(r.fundId, 1234);
  assert.strictEqual(r.name, "קרן");
  assert.strictEqual(r.monthlyYield, 1.23);
  assert.strictEqual(r.fee, null);
});

test("peer stats use the same track and rank by 3-year average", () => {
  const base = { classification: "קרנות השתלמות", specialization: "מניות", subSpecialization: "" };
  const rows = [
    { ...base, fundId: 1, name: "A", avgAnnual3y: 10, fee: 0.5 },
    { ...base, fundId: 2, name: "B", avgAnnual3y: 12, fee: 0.7 },
    { ...base, fundId: 3, name: "C", avgAnnual3y: 8, fee: 0.9 },
    { ...base, specialization: "כללי", fundId: 4, avgAnnual3y: 20, fee: 0.1 },
  ];
  const p = f.peerStats(rows[0], rows);
  assert.strictEqual(p.count, 3);
  assert.strictEqual(p.level, "track");
  assert.strictEqual(p.rank3y, 2);
  assert.strictEqual(p.avgFee, 0.7);
  assert.strictEqual(p.top[0].fundId, 2);
});

test("peer stats fall back to the specialization when the track is tiny", () => {
  const rows = [
    { classification: "x", specialization: "s", subSpecialization: "a", fundId: 1, avgAnnual3y: 1 },
    { classification: "x", specialization: "s", subSpecialization: "b", fundId: 2, avgAnnual3y: 2 },
    { classification: "x", specialization: "s", subSpecialization: "c", fundId: 3, avgAnnual3y: 3 },
  ];
  const p = f.peerStats(rows[0], rows);
  assert.strictEqual(p.level, "specialization");
  assert.strictEqual(p.count, 3);
});

test("index return between two month ends", () => {
  const closes = new Map([[202512, 100], [202603, 110]]);
  assert.strictEqual(f.indexReturn(closes, 202512, 202603), 10);
  assert.strictEqual(f.indexReturn(closes, 202511, 202603), null);
});
