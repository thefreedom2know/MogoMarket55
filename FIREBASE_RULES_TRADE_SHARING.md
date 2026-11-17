# Trade sharing - Realtime Database rules to add

Add these two nodes inside `"rules": { ... }` in Firebase Console -> Realtime Database -> Rules
(keep your existing rules for `users` etc.):

```json
"user-emails": {
  "$emailKey": {
    ".read": "auth != null",
    ".write": "auth != null && newData.val() === auth.uid && (!data.exists() || data.val() === auth.uid)"
  }
},
"trade-shares": {
  "$recipientUid": {
    ".read": "auth != null && auth.uid === $recipientUid",
    "$shareId": {
      ".write": "auth != null && (auth.uid === $recipientUid || (!data.exists() && newData.child('senderUid').val() === auth.uid))"
    }
  }
}
```

- `user-emails/<email with . replaced by ,>` = uid. Each user publishes their own on app start.
  Reads are per-key only, so emails cannot be listed.
- `trade-shares/<recipientUid>/<pushId>`: anyone signed in can add a share to an inbox
  (as themselves); only the owner can read or delete their inbox.
