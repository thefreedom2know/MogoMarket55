# Simulator leaderboard - Realtime Database rules to add

Add this node inside `"rules": { ... }` (Firebase Console -> Realtime Database -> Rules).
Without it the Challenges tab still works, but the leaderboard shows "Leaderboard unavailable".

```json
"leaderboard": {
  "$month": {
    ".indexOn": ["pct"],
    ".read": "auth != null",
    "$uid": {
      ".write": "auth != null && auth.uid === $uid",
      ".validate": "newData.hasChildren(['name', 'pct', 'equity', 'updatedAt'])",
      "name": { ".validate": "newData.isString() && newData.val().length >= 3 && newData.val().length <= 16" },
      "pct": { ".validate": "newData.isNumber() && newData.val() >= -100 && newData.val() <= 1000" },
      "equity": { ".validate": "newData.isNumber()" },
      "trades": { ".validate": "newData.isNumber()" },
      "updatedAt": { ".validate": "newData.isNumber()" }
    }
  }
}
```

- Each user can only write their own row; everybody signed in can read the top list.
- Results are reported by the app itself, so a determined user could fake a number.
  The range check above limits the damage; treat it as a for-fun leaderboard.
- The user's own `users/<uid>/leaderboard-profile` (nickname, opt-in) is covered by your existing
  `users/$uid` rule.
