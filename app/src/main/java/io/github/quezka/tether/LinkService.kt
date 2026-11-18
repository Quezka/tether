package io.github.quezka.tether

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Keeps the phone joined to the computer: finds it (its beacon), connects with the paired key, and carries out
 * what the computer asks (reply, send an SMS, answer a call, take a file or the clipboard). Runs in the foreground
 * with a quiet notification, so Android doesn't stop it.
 */
class LinkService : Service() {
    companion object {
        @Volatile var instance: LinkService? = null
        @Volatile var computers: Map<String, Protocol.Computer> = emptyMap()
        /** Per paired computer: what the link is doing now (shown in the app). */
        @Volatile var statuses: Map<String, String> = emptyMap()
        @Volatile var pairingStatus: String = ""
        @Volatile var pairingRequest: Pair<Protocol.Computer, String>? = null
        /** Compose reads this: any change above bumps it -> screen redraws (0.2 bug: no refresh after pairing). */
        val version = androidx.compose.runtime.mutableIntStateOf(0)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        /** To every connected computer. */
        fun send(m: JSONObject) { instance?.links?.values?.forEach { it.send(m) } }
    }

    private lateinit var store: Store
    /** One link per paired computer (0.3: a phone with several computers). */
    val links = java.util.concurrent.ConcurrentHashMap<String, Link>()
    private val loops = java.util.concurrent.ConcurrentHashMap<String, Thread>()
    @Volatile private var running = true
    private var lock: WifiManager.MulticastLock? = null
    private val incoming = HashMap<String, Pair<String, java.io.ByteArrayOutputStream>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("link", getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "link").setSmallIcon(R.drawable.ic_status)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.running)).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        lock = getSystemService(WifiManager::class.java).createMulticastLock("tether").apply { setReferenceCounted(false); acquire() }
        Thread(::listen, "tether-beacons").apply { isDaemon = true; start() }
        Thread(::superviseLoop, "tether-link").apply { isDaemon = true; start() }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        @Suppress("DEPRECATION")
        runCatching { getSystemService(TelephonyManager::class.java).listen(calls, PhoneStateListener.LISTEN_CALL_STATE) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        links.values.forEach { it.close() }
        lock?.release()
        runCatching { unregisterReceiver(battery) }
        instance = null
        super.onDestroy()
    }

    private fun changed() {
        android.os.Handler(mainLooper).post { version.intValue++ }
    }

    private fun status(id: String?, text: String) {
        if (id == null) pairingStatus = text else statuses = statuses + (id to text)
        changed()
    }

    /** Closes one computer's link (forgotten); its loop ends on its own. */
    fun drop(id: String) {
        links.remove(id)?.close()
        statuses = statuses - id
        changed()
    }

    private fun listen() {
        val socket = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(Protocol.BEACON_PORT)); soTimeout = 3000 } }
            .getOrNull() ?: return
        val buf = ByteArray(512)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { socket.receive(p) }.isFailure) continue
            val c = Protocol.parseBeacon(String(p.data, 0, p.length), p.address.hostAddress ?: continue) ?: continue
            if (computers[c.id] != c) { computers = computers + (c.id to c); changed() }
        }
        socket.close()
    }

    private fun phoneName() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    /** Starts a loop for each paired computer, and runs pairing requests. */
    private fun superviseLoop() {
        while (running) {
            for (p in store.pairings()) if (loops[p.id]?.isAlive != true)
                loops[p.id] = Thread({ connectLoop(p.id) }, "tether-${p.id}").apply { isDaemon = true; start() }
            pairingRequest?.let { request ->
                pairingRequest = null
                pair(request.first, request.second)
            }
            Thread.sleep(1000)
        }
    }

    private fun pair(computer: Protocol.Computer, code: String) {
        val l = Link(store, phoneName(), {}, {})
        status(null, getString(R.string.connecting))
        val why = l.run(computer.host, computer.port, code.toByteArray(), true)
        status(null, if (l.computerId != null && store.pairings().any { it.id == l.computerId }) getString(R.string.paired_with, computer.name)
            else if (why == "wrong code") getString(R.string.pair_failed) else getString(R.string.pair_failed))
    }

    private fun connectLoop(id: String) {
        var lanFails = 0
        while (running) {
            val p = store.pairings().firstOrNull { it.id == id } ?: return  // forgotten
            val seen = computers[id]
            val host = seen?.host ?: p.host
            val relay = store.relay?.let(Protocol::relayAddress)
            // lan first (beacon seen, or last host still answers); relay when away
            val away = seen == null && (host == null || lanFails >= 1)
            // no lan -> bluetooth first (close by, no internet needed), relay after
            if (away && store.bluetooth && tryBluetooth(id, p.key)) { lanFails = 0; Thread.sleep(2000); continue }
            val useRelay = relay != null && away
            if (!useRelay && host == null) { Thread.sleep(2000); continue }
            lateinit var l: Link
            l = Link(store, phoneName(), { handle(l, it) }) { name ->
                status(id, getString(if (l.viaRelay) R.string.connected_relay else R.string.connected_to, name))
                sendBattery(link = l)
            }
            l.computerId = id
            links[id] = l
            status(id, getString(R.string.connecting))
            val why = if (useRelay) l.run(relay!!.first, relay.second, p.key, false, Protocol.relayRoom(p.key))
                else l.run(host!!, seen?.port ?: 47821, p.key, false)
            if (!useRelay) lanFails = if (l.computerId != null && why == "closed") 0 else lanFails + 1
            else lanFails = 0  // back on lan next time if the beacon shows up
            if (links[id] === l) links.remove(id)
            if (store.pairings().any { it.id == id }) status(id, getString(R.string.not_connected))
            Thread.sleep(4000)
        }
    }

    /** One Bluetooth try at this computer: the address that worked last, else every bonded computer. True = was connected. */
    @SuppressLint("MissingPermission")
    private fun tryBluetooth(id: String, key: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val bonded = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
        val known = store.btAddress(id)
        val candidates = bonded.filter { it.address == known } + bonded.filter {
            it.address != known && it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
        }
        for (d in candidates) {
            lateinit var l: Link
            l = Link(store, phoneName(), { handle(l, it) }) { name ->
                status(id, getString(R.string.connected_bluetooth, name))
                sendBattery(link = l)
            }
            l.computerId = id
            l.viaBluetooth = true
            links[id] = l
            status(id, getString(R.string.connecting))
            l.runBluetooth(d, key)
            if (links[id] === l) links.remove(id)
            // wrong computer -> handshake fails before connect; right one -> remember it
            if (l.wasConnected) { store.setBtAddress(id, d.address); status(id, getString(R.string.not_connected)); return true }
        }
        return false
    }

    // ---- what the computer asks ----

    private fun handle(from: Link, m: JSONObject) {
        when (m.optString("t")) {
            "reply" -> NotifWatcher.reply(this, m.optString("key"), m.optString("text"))
            "dismiss" -> NotifWatcher.instance?.cancelNotification(m.optString("key"))
            "sms_send" -> sendSms(m.optString("addr"), m.optString("body"))
            "sms_get" -> from.send(smsList())
            "call_answer" -> calling { acceptRingingCall() }
            "call_reject" -> calling { endCall() }
            "clip" -> getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Tether", m.optString("text")))
            "file_start" -> incoming[m.optString("id")] = m.optString("name") to java.io.ByteArrayOutputStream()
            "file_piece" -> incoming[m.optString("id")]?.second?.write(Base64.decode(m.optString("data"), Base64.NO_WRAP))
            "file_end" -> incoming.remove(m.optString("id"))?.let { (name, data) -> saveDownload(name, data.toByteArray()) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun calling(action: TelecomManager.() -> Unit) {
        runCatching { getSystemService(TelecomManager::class.java).action() }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(address: String, body: String) {
        if (address.isBlank() || body.isBlank()) return
        runCatching {
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(address, null, sms.divideMessage(body), null, null)
        }
    }

    private fun contactName(number: String): String = runCatching {
        contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
    }.getOrNull().orEmpty()

    /** The latest conversations: up to 20 threads, 30 messages each. */
    private fun smsList(): JSONObject {
        val threads = LinkedHashMap<String, JSONArray>()
        runCatching {
            contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 600")?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    if (addr !in threads && threads.size >= 20) continue
                    val list = threads.getOrPut(addr) { JSONArray() }
                    if (list.length() >= 30) continue
                    list.put(JSONObject().put("in", c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX).put("body", c.getString(1) ?: "")
                        .put("at", c.getLong(2)))
                }
            }
        }
        val out = JSONArray()
        for ((addr, msgs) in threads) {
            val ordered = JSONArray((msgs.length() - 1 downTo 0).map { msgs.get(it) })
            out.put(JSONObject().put("addr", addr).put("name", contactName(addr)).put("messages", ordered))
        }
        return JSONObject().put("t", "sms_list").put("threads", out)
    }

    fun smsArrived(address: String, body: String, at: Long) {
        send(JSONObject().put("t", "sms_in").put("addr", address).put("name", contactName(address)).put("body", body).put("at", at))
    }

    private fun saveDownload(name: String, data: ByteArray) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, Protocol.safeName(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
        }
    }

    /** Send a file or text the user shared with Tether. */
    fun shareFile(name: String, bytes: ByteArray) {
        for (l in links.values.filter { it.connected }) shareFile(l, name, bytes)
    }

    private fun shareFile(l: Link, name: String, bytes: ByteArray) {
        val id = Protocol.randomBytes(6).joinToString("") { "%02x".format(it) }
        l.send(JSONObject().put("t", "file_start").put("id", id).put("name", name).put("size", bytes.size))
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + Protocol.FILE_PIECE, bytes.size)
            l.send(JSONObject().put("t", "file_piece").put("id", id).put("data", Base64.encodeToString(bytes.copyOfRange(at, end), Base64.NO_WRAP)))
            at = end
        }
        l.send(JSONObject().put("t", "file_end").put("id", id))
    }

    private var lastLevel = -1
    private fun sendBattery(intent: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)), link: Link? = null) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        lastLevel = level
        val m = JSONObject().put("t", "battery").put("level", level).put("charging", charging)
        if (link != null) link.send(m) else send(m)
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level != lastLevel) sendBattery(intent)
        }
    }

    @Suppress("DEPRECATION")
    private val calls = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            val s = when (state) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"; else -> "idle" }
            val n = number.orEmpty()
            send(JSONObject().put("t", "call").put("state", s).put("number", n).put("name", if (n.isEmpty()) "" else contactName(n)))
        }
    }
}
