package com.imagine.livelingo.security;

import android.app.Activity;
import java.util.List;

/** Small controller that keeps protected meeting-history flows out of MainActivity. */
public final class MeetingHistoryController {
    public interface Listener {
        void onList(List<MeetingVaultRepository.Item> items);
        void onMeeting(EncryptedMeetingVault.StoredMeeting meeting);
        void onError(String message);
        void onDeleted();
    }

    private final Activity activity;
    private final MeetingVaultRepository repository;
    private final Listener listener;
    private String pendingMeetingId;

    public MeetingHistoryController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.repository = new MeetingVaultRepository(activity);
    }

    public void refresh() {
        new Thread(() -> {
            try {
                List<MeetingVaultRepository.Item> items = repository.list();
                activity.runOnUiThread(() -> listener.onList(items));
            } catch (Exception e) {
                activity.runOnUiThread(() -> listener.onError("Не удалось открыть защищённую историю"));
            }
        }, "meeting-vault-list").start();
    }

    public boolean requestOpen(String id, int requestCode) {
        pendingMeetingId = id;
        return SecureMeetingAccess.requestDeviceUnlock(activity, requestCode);
    }

    public void onAuthenticationSucceeded() {
        String id = pendingMeetingId;
        pendingMeetingId = null;
        if (id == null) return;
        new Thread(() -> {
            try {
                EncryptedMeetingVault.StoredMeeting meeting = repository.load(id);
                if (meeting == null) {
                    activity.runOnUiThread(() -> listener.onError("Встреча не найдена"));
                    return;
                }
                activity.runOnUiThread(() -> listener.onMeeting(meeting));
            } catch (Exception e) {
                activity.runOnUiThread(() -> listener.onError("Не удалось расшифровать встречу"));
            }
        }, "meeting-vault-open").start();
    }

    public void delete(String id) {
        repository.delete(id);
        listener.onDeleted();
        refresh();
    }

    public void wipeAll() {
        repository.wipeAll();
        listener.onDeleted();
        refresh();
    }
}
