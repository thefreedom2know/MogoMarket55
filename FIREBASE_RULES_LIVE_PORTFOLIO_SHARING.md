# Live portfolio sharing - Realtime Database rules to add

"Share full portfolio (live)" no longer sends a copy. It lets the recipient read your
open positions, closed trades and cash directly, so every buy, edit and sell shows up
for them right away, until you stop sharing (Portfolio -> Share -> "Who sees my portfolio").

Add these rules in Firebase Console -> Realtime Database -> Rules. Keep your existing rules
(including `user-emails` and `trade-shares` from FIREBASE_RULES_TRADE_SHARING.md).

## 1. Two new top-level nodes, inside `"rules": { ... }`

```json
"portfolio-viewers": {
  "$ownerUid": {
    ".read": "auth != null && auth.uid === $ownerUid",
    "$viewerUid": {
      ".write": "auth != null && (auth.uid === $ownerUid || (auth.uid === $viewerUid && !newData.exists()))"
    }
  }
},
"portfolio-shared-with-me": {
  "$viewerUid": {
    ".read": "auth != null && auth.uid === $viewerUid",
    "$ownerUid": {
      ".write": "auth != null && (auth.uid === $ownerUid || (auth.uid === $viewerUid && !newData.exists()))"
    }
  }
}
```

## 2. Three read grants inside your existing `users/$uid` rule

Your `users` rule probably looks like `"users": { "$uid": { ".read": ..., ".write": ... } }`.
Add these three children **inside** `"$uid": { ... }`, next to the existing `.read` / `.write`
(rename `$uid` below if your rule uses a different variable name):

```json
"portfolio-stocks": {
  ".read": "auth != null && root.child('portfolio-viewers').child($uid).child(auth.uid).exists()"
},
"closed-trades": {
  ".read": "auth != null && root.child('portfolio-viewers').child($uid).child(auth.uid).exists()"
},
"portfolio-cash": {
  ".read": "auth != null && root.child('portfolio-viewers').child($uid).child(auth.uid).exists()"
}
```

Only these three nodes become readable, and only by people you added. Writing stays owner-only
(your existing `.write` rule is unchanged), and the rest of `users/<uid>` (settings, watchlist,
simulator, email settings, etc.) stays private.

## How it works

- `portfolio-viewers/<ownerUid>/<viewerUid>` = `{email, since}`: who may read the owner's portfolio.
  Only the owner can add someone; the owner or that viewer can remove the entry.
- `portfolio-shared-with-me/<viewerUid>/<ownerUid>` = `{ownerEmail, since}`: the viewer's list,
  shown at the top of "Shared" with a LIVE tag. Long-press it to stop following.
- When sharing stops, Firebase immediately cuts the viewer's access; an open live screen shows
  "no longer sharing".
- "Share one trade" still sends a one-time copy, as before.
