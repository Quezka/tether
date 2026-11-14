package io.github.quezka.tether

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Mirrors the phone's notifications to the computer (with notification access), and replies from there. */
class NotifWatcher : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifWatcher? = null
        private val replies = HashMap<String, Notification.Action>()

        fun reply(context: Context, key: String, text: String) {
            val action = replies[key] ?: return
            val inputs = action.remoteInputs ?: return
            val intent = Intent()
            val results = Bundle().apply { for (r in inputs) putCharSequence(r.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            runCatching { action.actionIntent.send(context, 0, intent) }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (reply != null) replies[sbn.key] = reply
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        LinkService.send(JSONObject().put("t", "notif").put("key", sbn.key).put("app", app).put("title", title)
            .put("text", text).put("reply", reply != null))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        replies.remove(sbn.key)
        LinkService.send(JSONObject().put("t", "notif_removed").put("key", sbn.key))
    }
}
