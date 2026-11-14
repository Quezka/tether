package io.github.quezka.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** A text message arrived: on to the computer. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val first = parts.firstOrNull() ?: return
        LinkService.instance?.smsArrived(first.originatingAddress.orEmpty(), parts.joinToString("") { it.messageBody.orEmpty() },
            first.timestampMillis)
    }
}

/** Starts the link after the phone boots or Tether updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).paired) runCatching { LinkService.start(context) }
    }
}
