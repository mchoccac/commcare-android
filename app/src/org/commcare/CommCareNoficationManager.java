package org.commcare;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.support.v4.app.NotificationCompat;

import org.commcare.activities.MessageActivity;
import org.commcare.dalvik.R;
import org.commcare.utils.PopupHandler;
import org.commcare.views.notifications.NotificationClearReceiver;
import org.commcare.views.notifications.NotificationMessage;
import org.javarosa.core.services.locale.Localization;

import java.util.ArrayList;
import java.util.Vector;

import static android.content.Context.NOTIFICATION_SERVICE;

/**
 * Handles displaying and clearing pinned notifications for CommCare
 */
public class CommCareNoficationManager {
    private static final String ACTION_PURGE_NOTIFICATIONS = "CommCareApplication_purge";
    private final ArrayList<NotificationMessage> pendingMessages = new ArrayList<>();
    public static final int MESSAGE_NOTIFICATION = R.string.notification_message_title;

    /**
     * Handler to receive notifications and show them the user using toast.
     */
    private final PopupHandler toaster;
    private final Context context;

    public CommCareNoficationManager(Context context) {
        this.context = context;
        toaster = new PopupHandler(context);
    }


    private void updateMessageNotification() {
        NotificationManager mNM = (NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
        synchronized (pendingMessages) {
            if (pendingMessages.size() == 0) {
                mNM.cancel(MESSAGE_NOTIFICATION);
                return;
            }

            String title = pendingMessages.get(0).getTitle();

            // The PendingIntent to launch our activity if the user selects this notification
            Intent i = new Intent(context, MessageActivity.class);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, i, 0);

            String additional = pendingMessages.size() > 1 ? Localization.get("notifications.prompt.more", new String[]{String.valueOf(pendingMessages.size() - 1)}) : "";

            Notification messageNotification = new NotificationCompat.Builder(context)
                    .setContentTitle(title)
                    .setContentText(Localization.get("notifications.prompt.details", new String[]{additional}))
                    .setSmallIcon(R.drawable.notification)
                    .setNumber(pendingMessages.size())
                    .setContentIntent(contentIntent)
                    .setDeleteIntent(PendingIntent.getBroadcast(context, 0, new Intent(context, NotificationClearReceiver.class), 0))
                    .setOngoing(true)
                    .setWhen(System.currentTimeMillis())
                    .build();

            mNM.notify(MESSAGE_NOTIFICATION, messageNotification);
        }
    }

    public void clearNotifications(String category) {
        synchronized (pendingMessages) {
            NotificationManager mNM = (NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
            Vector<NotificationMessage> toRemove = new Vector<>();
            for (NotificationMessage message : pendingMessages) {
                if (category == null || category.equals(message.getCategory())) {
                    toRemove.add(message);
                }
            }

            for (NotificationMessage message : toRemove) {
                pendingMessages.remove(message);
            }
            if (pendingMessages.size() == 0) {
                mNM.cancel(MESSAGE_NOTIFICATION);
            } else {
                updateMessageNotification();
            }
        }
    }

    public ArrayList<NotificationMessage> purgeNotifications() {
        synchronized (pendingMessages) {
            context.sendBroadcast(new Intent(ACTION_PURGE_NOTIFICATIONS));
            ArrayList<NotificationMessage> cloned = (ArrayList<NotificationMessage>)pendingMessages.clone();
            clearNotifications(null);
            return cloned;
        }
    }

    public void reportNotificationMessage(NotificationMessage message) {
        reportNotificationMessage(message, false);
    }

    public void reportNotificationMessage(final NotificationMessage message, boolean showToast) {
        synchronized (pendingMessages) {
            // Make sure there is no matching message pending
            for (NotificationMessage msg : pendingMessages) {
                if (msg.equals(message)) {
                    // If so, bail.
                    return;
                }
            }
            if (showToast) {
                Bundle b = new Bundle();
                b.putParcelable("message", message);
                Message m = Message.obtain(toaster);
                m.setData(b);
                toaster.sendMessage(m);
            }

            // Otherwise, add it to the queue, and update the notification
            pendingMessages.add(message);
            updateMessageNotification();
        }
    }
}
