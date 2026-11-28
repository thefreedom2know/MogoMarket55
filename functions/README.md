# MogoMarket server functions

Holds every secret the app needs (Finnhub, Groq and Gemini keys, and the Gmail app password).
Anything inside an APK can be extracted, so with these functions the app ships with **no keys**.

## One-time setup

Needs the Firebase **Blaze** plan (pay as you go; this usage is a few cents at most) and Node 22 (Node 20 was decommissioned by Google Cloud on 2026-10-30).

```bash
npm install -g firebase-tools
firebase login
firebase use --add            # pick your MogoMarket Firebase project
cd functions && npm install && cd ..

firebase functions:secrets:set FINNHUB_KEY
firebase functions:secrets:set GROQ_API_KEY
firebase functions:secrets:set GEMINI_API_KEY      # can be left empty
firebase functions:secrets:set SMTP_EMAIL
firebase functions:secrets:set SMTP_PASSWORD       # Gmail App Password

firebase deploy --only functions
```

(Each `secrets:set` command asks you to paste the value - nothing is stored in the repo.)

## What the app calls

The app has no keys and no local fallback: it always calls these functions (users must be logged in).
Old `FINNHUB_KEY`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `SMTP_EMAIL` and `SMTP_PASSWORD` lines in
`local.properties` are ignored and can be deleted.

| App feature        | Function            | Limits (per user)                         |
|--------------------|---------------------|-------------------------------------------|
| AI chat / analysis | `aiChat`            | 12 / minute, 200 / day                    |
| Daily summary mail | `sendSummaryEmail`  | 6 / day, only to the user's own address   |
| Quotes, search, company profile (Finnhub) | `finnhub` | 120 / minute; answers cached on the server |

When the user is not logged in, quotes fall back to free Yahoo data.

## Password reset

The forgot-password screen uses Firebase Auth's built-in reset email (no SMTP involved).
Change its wording, sender name or language in the Firebase console:
Authentication > Templates > Password reset.

## Notes

- The functions use the default region `us-central1`, which is what the app calls.
- Server email is only sent to the email address of the logged-in account (anti-spam).
  A different recipient in Settings needs a change in `index.js`.
- The Finnhub proxy only allows the `quote`, `search` and `stock/profile2` endpoints.
