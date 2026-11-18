# MogoMarket server functions

Moves the secrets (Groq / Gemini keys and the Gmail app password) off the phone.
Anything inside an APK can be extracted, so with these functions the app ships with **no keys**.

## One-time setup

Needs the Firebase **Blaze** plan (pay as you go; this usage is a few cents at most) and Node 20.

```bash
npm install -g firebase-tools
firebase login
firebase use --add            # pick your MogoMarket Firebase project
cd functions && npm install && cd ..

firebase functions:secrets:set GROQ_API_KEY
firebase functions:secrets:set GEMINI_API_KEY      # can be left empty
firebase functions:secrets:set SMTP_EMAIL
firebase functions:secrets:set SMTP_PASSWORD       # Gmail App Password

firebase deploy --only functions
```

(Each `secrets:set` command asks you to paste the value - nothing is stored in the repo.)

## Then, in the app

Delete `GROQ_API_KEY`, `GEMINI_API_KEY`, `SMTP_EMAIL` and `SMTP_PASSWORD` from `local.properties`
and rebuild. When the app has no local key it automatically calls the functions:

| App feature        | Function            | Limits (per user)                         |
|--------------------|---------------------|-------------------------------------------|
| AI chat / analysis | `aiChat`            | 12 / minute, 200 / day                    |
| Daily summary mail | `sendSummaryEmail`  | 6 / day, only to the user's own address   |

Keeping the keys in `local.properties` still works (local mode) - useful for debugging.

## Notes

- The functions use the default region `us-central1`, which is what the app calls.
- Server email is only sent to the email address of the logged-in account (anti-spam).
  A different recipient in Settings needs local SMTP keys or a change in `index.js`.
- The forgot-password screen still uses the local SMTP keys; Firebase Auth's built-in
  password-reset email is the better long-term replacement.
