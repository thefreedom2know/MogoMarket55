package com.mogomarket.app;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.GenericTypeIndicator;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Uploads trade screenshots to Firebase Storage and records them on the trade. */
public class TradeScreenshotStorage {
    public interface Callback {
        void onResult(boolean success, String message);
    }

    /**
     * @param userRef   users/{uid} database reference
     * @param tradePath path of the trade relative to userRef, e.g. "portfolio-stocks/AAPL"
     */
    public static void uploadAndAttach(Context context, Uri uri, String userId,
                                       DatabaseReference userRef, String tradePath,
                                       Callback callback) {
        if (uri == null || userId == null || userRef == null || tradePath == null) {
            report(callback, false, "Could not upload the screenshot");
            return;
        }
        final String storagePath = "users/" + userId + "/trade-screenshots/"
                + UUID.randomUUID() + ".jpg";
        final StorageReference fileRef = FirebaseStorage.getInstance().getReference()
                .child(storagePath);
        fileRef.putFile(uri)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return fileRef.getDownloadUrl();
                })
                .addOnSuccessListener(downloadUrl ->
                        attach(userRef.child(tradePath), storagePath, downloadUrl.toString(), callback))
                .addOnFailureListener(error ->
                        report(callback, false, "Upload failed: " + error.getMessage()));
    }

    private static void attach(DatabaseReference tradeRef, String storagePath, String url,
                               Callback callback) {
        tradeRef.get().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || !task.getResult().exists()) {
                report(callback, false, "Trade not found, screenshot was not saved");
                return;
            }
            DataSnapshot snapshot = task.getResult();
            List<String> paths = readList(snapshot.child("screenshotPaths"));
            List<String> urls = readList(snapshot.child("screenshotUrls"));
            paths.add(storagePath);
            urls.add(url);
            Map<String, Object> update = new HashMap<>();
            update.put("screenshotPaths", paths);
            update.put("screenshotUrls", urls);
            tradeRef.updateChildren(update)
                    .addOnSuccessListener(unused -> report(callback, true, "Screenshot uploaded"))
                    .addOnFailureListener(error ->
                            report(callback, false, "Could not save screenshot: " + error.getMessage()));
        });
    }

    @NonNull
    private static List<String> readList(DataSnapshot snapshot) {
        List<String> list = snapshot.getValue(new GenericTypeIndicator<List<String>>() { });
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static void deleteAll(List<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            FirebaseStorage.getInstance().getReference().child(path).delete();
        }
    }

    private static void report(Callback callback, boolean success, String message) {
        if (callback != null) callback.onResult(success, message);
    }
}
