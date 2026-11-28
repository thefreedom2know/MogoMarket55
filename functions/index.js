"use strict";

/**
 * Server side of MogoMarket: keeps the AI keys and the email password OFF the phone.
 *
 *  - aiChat           : Groq first, Gemini as a fallback (same behaviour as the app's local mode)
 *  - sendSummaryEmail : sends the daily and weekly summaries through Gmail SMTP
 *  - finnhub          : read-only proxy for Finnhub quotes, symbol search and company profiles
 *
 * Both require a logged-in user and are rate limited per user.
 * Secrets are stored with `firebase functions:secrets:set` (see README.md).
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");

admin.initializeApp();

const GROQ_API_KEY = defineSecret("GROQ_API_KEY");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const SMTP_EMAIL = defineSecret("SMTP_EMAIL");
const SMTP_PASSWORD = defineSecret("SMTP_PASSWORD");
const FINNHUB_KEY = defineSecret("FINNHUB_KEY");

const GROQ_MODELS = [
  "openai/gpt-oss-120b",
  "llama-3.3-70b-versatile",
  "openai/gpt-oss-20b",
  "llama-3.1-8b-instant",
];
const GEMINI_MODEL = "gemini-flash-latest";

/**
 * Value of a secret, or "" when it is unset or a placeholder. Every secret must exist for
 * `firebase deploy` to succeed, so keys you don't use can be set to "none".
 */
function secretValue(secret) {
  const v = String(secret.value() || "").trim();
  return v === "" || v.toLowerCase() === "none" || v === "-" ? "" : v;
}

/** Gives back one use of a rate-limit bucket (used when the action itself failed). */
async function refundRateLimit(uid, bucket) {
  const ref = admin.database().ref(`rate-limits/${uid}/${bucket}`);
  await ref.transaction((current) => {
    if (!current || !current.n) return current;
    return { ...current, n: current.n - 1 };
  });
}

/** Fixed-window counter per user, stored in the Realtime Database (admin access only). */
async function rateLimit(uid, bucket, max, windowMs) {
  const ref = admin.database().ref(`rate-limits/${uid}/${bucket}`);
  const now = Date.now();
  const result = await ref.transaction((current) => {
    let state = current || { start: now, n: 0 };
    if (now - state.start > windowMs) state = { start: now, n: 0 };
    if (state.n >= max) return; // abort
    state.n += 1;
    return state;
  });
  if (!result.committed) {
    throw new HttpsError("resource-exhausted", "Limit reached, please try again later.");
  }
}

function requireAuth(request) {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Please log in first.");
  }
  return request.auth.uid;
}

async function askGroq(system, turns, key) {
  const messages = [];
  if (system) messages.push({ role: "system", content: system });
  for (const t of turns) messages.push({ role: t.role, content: t.text });

  for (const model of GROQ_MODELS) {
    const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model, messages, temperature: 0.4 }),
    });
    if (res.status === 404 || res.status === 403 || res.status === 400) continue; // no access: next model
    if (!res.ok) throw new Error(`Groq ${res.status}`);
    const json = await res.json();
    const text = json.choices?.[0]?.message?.content?.trim();
    if (text) return text;
  }
  throw new Error("Groq has no usable model");
}

async function askGemini(system, turns, key) {
  const body = {
    contents: turns.map((t) => ({
      role: t.role === "assistant" ? "model" : "user",
      parts: [{ text: t.text }],
    })),
  };
  if (system) body.systemInstruction = { parts: [{ text: system }] };
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify(body),
    }
  );
  if (!res.ok) throw new Error(`Gemini ${res.status}`);
  const json = await res.json();
  const text = (json.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("").trim();
  if (!text) throw new Error("Gemini returned no text");
  return text;
}

exports.aiChat = onCall(
  { secrets: [GROQ_API_KEY, GEMINI_API_KEY], maxInstances: 5, timeoutSeconds: 90 },
  async (request) => {
    const uid = requireAuth(request);
    const data = request.data || {};
    const system = typeof data.system === "string" ? data.system.slice(0, 20000) : "";
    const raw = Array.isArray(data.turns) ? data.turns.slice(-30) : [];
    const turns = raw
      .filter((t) => t && (t.role === "user" || t.role === "assistant") && typeof t.text === "string")
      .map((t) => ({ role: t.role, text: t.text.slice(0, 12000) }));
    if (turns.length === 0) throw new HttpsError("invalid-argument", "No message to answer.");

    await rateLimit(uid, "ai-minute", 12, 60 * 1000);
    await rateLimit(uid, "ai-day", 200, 24 * 60 * 60 * 1000);

    const groq = secretValue(GROQ_API_KEY);
    const gemini = secretValue(GEMINI_API_KEY);
    let lastError = "No AI key is configured on the server.";
    if (groq) {
      try {
        return { text: await askGroq(system, turns, groq) };
      } catch (e) {
        lastError = String(e.message || e);
      }
    }
    if (gemini) {
      try {
        return { text: await askGemini(system, turns, gemini) };
      } catch (e) {
        lastError = String(e.message || e);
      }
    }
    throw new HttpsError("unavailable", `AI is unavailable (${lastError}).`);
  }
);

exports.sendSummaryEmail = onCall(
  { secrets: [SMTP_EMAIL, SMTP_PASSWORD], maxInstances: 5, timeoutSeconds: 60 },
  async (request) => {
    const uid = requireAuth(request);
    const data = request.data || {};
    const to = String(data.to || "").trim().toLowerCase();
    const subject = String(data.subject || "").slice(0, 200);
    const html = String(data.html || "").slice(0, 300000);
    const text = String(data.text || "").slice(0, 100000);

    // Anti-abuse: the mail may only go to the user's own verified account address.
    const accountEmail = (request.auth.token.email || "").toLowerCase();
    if (!accountEmail) {
      throw new HttpsError("failed-precondition", "Your account has no email address.");
    }
    if (to !== accountEmail) {
      throw new HttpsError(
        "permission-denied",
        "Server email can only be sent to your own account address (" + accountEmail + ")."
      );
    }
    if (!subject || !(html || text)) throw new HttpsError("invalid-argument", "Empty email.");

    const smtpUser = secretValue(SMTP_EMAIL);
    // Gmail shows app passwords in groups of 4 ("abcd efgh ..."): the spaces are not part of it.
    const smtpPass = secretValue(SMTP_PASSWORD).replace(/\s+/g, "");
    if (!smtpUser || !smtpPass) {
      throw new HttpsError(
        "failed-precondition",
        "Email is not set up on the server: set the SMTP_EMAIL and SMTP_PASSWORD secrets " +
          "(see functions/README.md) and deploy again."
      );
    }

    await rateLimit(uid, "mail-day", 10, 24 * 60 * 60 * 1000);

    const transporter = nodemailer.createTransport({
      host: "smtp.gmail.com",
      port: 587,
      secure: false,
      auth: { user: smtpUser, pass: smtpPass },
    });
    try {
      await transporter.sendMail({
        from: `"MogoMarket" <${smtpUser}>`,
        to,
        subject,
        text,
        html,
      });
    } catch (e) {
      // A failed send must not use up the daily limit, or fixing the setup and retrying
      // would be blocked until tomorrow.
      await refundRateLimit(uid, "mail-day").catch(() => {});
      const msg = String(e.message || e);
      if (e.code === "EAUTH" || /Invalid login|BadCredentials|Username and Password/i.test(msg)) {
        throw new HttpsError(
          "failed-precondition",
          "Gmail refused the login. SMTP_PASSWORD must be a Gmail App Password " +
            "(Google account > Security > 2-Step Verification > App passwords), " +
            "not the normal Gmail password."
        );
      }
      throw new HttpsError("internal", "Sending failed: " + msg);
    }
    return { ok: true };
  }
);

// ---- Finnhub proxy ------------------------------------------------------------------------

/** Allowed Finnhub paths, the query parameters each one accepts, and how long to cache it. */
const FINNHUB_PATHS = {
  "/api/v1/quote": { params: ["symbol"], cacheMs: 15 * 1000 },
  "/api/v1/search": { params: ["q", "exchange"], cacheMs: 60 * 60 * 1000 },
  "/api/v1/stock/profile2": { params: ["symbol", "isin", "cusip"], cacheMs: 24 * 60 * 60 * 1000 },
};

// Per-instance cache and per-user minute counter. They are kept in memory (not the database)
// because quotes are requested often; with few instances this is close enough.
const finnhubCache = new Map();
const finnhubCalls = new Map();

function finnhubMemoryLimit(uid, max) {
  const now = Date.now();
  let state = finnhubCalls.get(uid);
  if (!state || now - state.start > 60 * 1000) state = { start: now, n: 0 };
  state.n += 1;
  finnhubCalls.set(uid, state);
  if (state.n > max) {
    throw new HttpsError("resource-exhausted", "Too many quote requests, please wait a minute.");
  }
}

exports.finnhub = onCall(
  { secrets: [FINNHUB_KEY], maxInstances: 10, timeoutSeconds: 20 },
  async (request) => {
    const uid = requireAuth(request);
    const data = request.data || {};
    const path = String(data.path || "");
    const spec = FINNHUB_PATHS[path];
    if (!spec) throw new HttpsError("invalid-argument", "This Finnhub request is not allowed.");

    const query = new URLSearchParams();
    const given = data.params && typeof data.params === "object" ? data.params : {};
    for (const name of spec.params) {
      if (given[name] !== undefined && given[name] !== null) {
        query.set(name, String(given[name]).slice(0, 100));
      }
    }
    const cacheKey = path + "?" + query.toString();
    const cached = finnhubCache.get(cacheKey);
    if (cached && Date.now() - cached.at < spec.cacheMs) {
      return { status: cached.status, body: cached.body };
    }

    finnhubMemoryLimit(uid, 120);

    const key = secretValue(FINNHUB_KEY);
    if (!key) throw new HttpsError("failed-precondition", "No Finnhub key is configured on the server.");
    query.set("token", key);
    const res = await fetch(`https://finnhub.io${path}?${query.toString()}`);
    const body = await res.text();
    if (res.ok) {
      if (finnhubCache.size > 5000) finnhubCache.clear();
      finnhubCache.set(cacheKey, { at: Date.now(), status: res.status, body });
    }
    // Never echo the upstream body on errors: it can mention the key.
    return res.ok ? { status: res.status, body } : { status: res.status, body: "{}" };
  }
);
