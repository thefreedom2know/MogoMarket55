"use strict";

/**
 * Server side of MogoMarket: keeps the AI keys and the email password OFF the phone.
 *
 *  - aiChat           : Groq first, Gemini as a fallback (same behaviour as the app's local mode)
 *  - sendSummaryEmail : sends the daily summary through Gmail SMTP
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

const GROQ_MODELS = [
  "openai/gpt-oss-120b",
  "llama-3.3-70b-versatile",
  "openai/gpt-oss-20b",
  "llama-3.1-8b-instant",
];
const GEMINI_MODEL = "gemini-flash-latest";

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

    const groq = GROQ_API_KEY.value();
    const gemini = GEMINI_API_KEY.value();
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

    await rateLimit(uid, "mail-day", 6, 24 * 60 * 60 * 1000);

    const transporter = nodemailer.createTransport({
      host: "smtp.gmail.com",
      port: 587,
      secure: false,
      auth: { user: SMTP_EMAIL.value(), pass: SMTP_PASSWORD.value() },
    });
    try {
      await transporter.sendMail({
        from: `"MogoMarket" <${SMTP_EMAIL.value()}>`,
        to,
        subject,
        text,
        html,
      });
    } catch (e) {
      throw new HttpsError("internal", "Sending failed: " + String(e.message || e));
    }
    return { ok: true };
  }
);
