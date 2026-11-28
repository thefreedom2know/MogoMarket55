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
firebase functions:secrets:set GEMINI_API_KEY      # type none if you don't use it
firebase functions:secrets:set SMTP_EMAIL          # the Gmail address that sends the mail
firebase functions:secrets:set SMTP_PASSWORD       # a Gmail App Password, not the normal password

firebase deploy --only functions
```

(Each `secrets:set` command asks you to paste the value - nothing is stored in the repo.)

**All five secrets must exist, or `firebase deploy` fails for every function** (including the
summary email). For a key you don't use, set the value `none`. An empty value is rejected.

If pasting into the hidden prompt doesn't work (common in Windows PowerShell), create or update
the secret in the browser instead: Google Cloud console > Security > Secret Manager >
Create secret (or open the secret > + New version), then run `firebase deploy --only functions`.

**Gmail App Password:** turn on 2-Step Verification for the sending Gmail account, then open
Google account > Security > 2-Step Verification > App passwords, create one, and use those
16 letters as `SMTP_PASSWORD` (spaces are fine). The normal Gmail password is refused by Gmail.

## What the app calls

The app has no keys and no local fallback: it always calls these functions (users must be logged in).
Old `FINNHUB_KEY`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `SMTP_EMAIL` and `SMTP_PASSWORD` lines in
`local.properties` are ignored and can be deleted.

| App feature        | Function            | Limits (per user)                         |
|--------------------|---------------------|-------------------------------------------|
| AI chat / analysis | `aiChat`            | 12 / minute, 200 / day                    |
| Daily / weekly summary mail | `sendSummaryEmail` | 10 sent / day (failed tries don't count), only to the user's own address |
| Quotes, search, company profile (Finnhub) | `finnhub` | 120 / minute; answers cached on the server |

| Funds: find a fund by number or name | `fundsLookup` | 30 / minute |
| Funds: refresh returns, fees, peers  | `fundsRefresh` | 30 / day |
| Funds: daily check for new monthly data | `fundsMonthlyUpdate` (scheduled, 07:30 Israel time) | - |

When the user is not logged in, quotes fall back to free Yahoo data.

## Savings funds (קופות גמל, השתלמות, פנסיה)

`funds.js` reads the Capital Market Authority's open data on data.gov.il (Gemel-Net, Pension-Net,
Bituah-Net; no key needed) and Yahoo month-end closes of TA-125 and S&P 500.

- The app saves each fund in `users/<uid>/funds/<key>` (fund number, balance and the month it is
  correct for, fees, monthly deposit). The server writes only the `market` child of each fund:
  latest month's returns, 12-month return, fees, exposures, the average of funds in the same track
  (`peers`), index returns, and `estimatedValue` (balance grown by each later month's gross return,
  minus 1/12 of the yearly fee, plus the monthly deposit).
- `fund-watch/<uid>` lists users with funds, for the scheduled job; `fund-updates` holds the newest
  month seen per dataset, so the job only does real work when a new month is published (or weekly).
- Both nodes are written by the server only. **No Realtime Database rule changes are needed**:
  `users/<uid>/funds` is covered by your existing `users/$uid` rule.
- The scheduled function uses Cloud Scheduler (included in Blaze; 3 free jobs per month).

Deploy with the same `firebase deploy --only functions`. Tests: `cd functions && npm test`.

## Password reset

The forgot-password screen uses Firebase Auth's built-in reset email (no SMTP involved).
Change its wording, sender name or language in the Firebase console:
Authentication > Templates > Password reset.

## Notes

- The functions use the default region `us-central1`, which is what the app calls.
- Server email is only sent to the email address of the logged-in account (anti-spam).
  A different recipient in Settings needs a change in `index.js`.
- The Finnhub proxy only allows the `quote`, `search` and `stock/profile2` endpoints.
