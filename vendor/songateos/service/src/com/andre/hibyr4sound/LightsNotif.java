package com.andre.hibyr4sound;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification light (colour LED and/or back light): enabled by Lights only while one of the two notification
 * options is on. Counts new, clearable notifications of normal or higher importance; ignores ongoing ones,
 * media / service / progress notifications and our own.
 */
public class LightsNotif extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankings) {
        if (sbn == null || !sbn.isClearable() || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_SERVICE.equals(n.category)
                || Notification.CATEGORY_PROGRESS.equals(n.category)) return;
        Ranking r = new Ranking();
        if (rankings != null && rankings.getRanking(sbn.getKey(), r)
                && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;
        Lights.onNotification();
    }
}
