package com.mogomarket.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live portfolio sharing.
 *
 * Instead of sending a copy of the portfolio, the owner grants a viewer read access to their
 * own {@code users/<ownerUid>/portfolio-stocks}, {@code closed-trades} and {@code portfolio-cash}.
 * The viewer listens to those nodes directly, so every buy, edit and sell shows up immediately.
 *
 * Database layout (see FIREBASE_RULES_LIVE_PORTFOLIO_SHARING.md for the matching rules):
 * <ul>
 *   <li>{@code portfolio-viewers/<ownerUid>/<viewerUid>} = {email, since}: who may read the
 *       owner's portfolio. Only the owner can add; owner or viewer can remove.</li>
 *   <li>{@code portfolio-shared-with-me/<viewerUid>/<ownerUid>} = {ownerEmail, since}: the
 *       viewer's list of live portfolios. Written together with the entry above.</li>
 * </ul>
 */
final class LivePortfolioShare {

    static final String VIEWERS = "portfolio-viewers";
    static final String SHARED_WITH_ME = "portfolio-shared-with-me";

    private LivePortfolioShare() {}

    static DatabaseReference root() {
        return FirebaseDatabase.getInstance().getReference();
    }

    static DatabaseReference viewersOf(@NonNull String ownerUid) {
        return root().child(VIEWERS).child(ownerUid);
    }

    static DatabaseReference sharedWith(@NonNull String viewerUid) {
        return root().child(SHARED_WITH_ME).child(viewerUid);
    }

    /** Starts live sharing of the owner's portfolio with one viewer. */
    static Task<Void> start(@NonNull String ownerUid, @Nullable String ownerEmail,
                            @NonNull String viewerUid, @NonNull String viewerEmail) {
        long now = System.currentTimeMillis();
        Map<String, Object> viewer = new LinkedHashMap<>();
        viewer.put("email", viewerEmail.trim().toLowerCase(java.util.Locale.US));
        viewer.put("since", now);
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("ownerEmail", ownerEmail == null ? "" : ownerEmail);
        owner.put("since", now);

        Map<String, Object> updates = new HashMap<>();
        updates.put(VIEWERS + "/" + ownerUid + "/" + viewerUid, viewer);
        updates.put(SHARED_WITH_ME + "/" + viewerUid + "/" + ownerUid, owner);
        return root().updateChildren(updates);
    }

    /** Ends live sharing. Either side may call it (owner stops sharing, viewer leaves). */
    static Task<Void> stop(@NonNull String ownerUid, @NonNull String viewerUid) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(VIEWERS + "/" + ownerUid + "/" + viewerUid, null);
        updates.put(SHARED_WITH_ME + "/" + viewerUid + "/" + ownerUid, null);
        return root().updateChildren(updates);
    }
}
