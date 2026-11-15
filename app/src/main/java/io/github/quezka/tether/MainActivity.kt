package io.github.quezka.tether

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.quezka.tether.ui.TetherTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LinkService.start(this)
        LinkService.onChange = { runOnUiThread { tick++ } }
        setContent { TetherTheme { Surface(Modifier.fillMaxSize()) { Screen(tick) } } }
    }

    override fun onResume() { super.onResume(); tick++ }

    @Composable
    private fun Screen(tick: Int) {
        val store = remember { Store(this) }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Panel(stringResource(R.string.status)) {
                Text(LinkService.status.ifEmpty { stringResource(R.string.not_connected) }, style = MaterialTheme.typography.titleMedium)
                if (store.paired) {
                    Text(stringResource(R.string.paired_with, store.computerName ?: "?"))
                    OutlinedButton(onClick = { store.forget(); LinkService.instance?.link?.close(); this@MainActivity.tick++ }) {
                        Text(stringResource(R.string.forget))
                    }
                    Button(onClick = {
                        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)
                        if (text != null) LinkService.send(JSONObject().put("t", "clip").put("text", text.toString()))
                    }) { Text(stringResource(R.string.send_clipboard)) }
                }
            }
            Panel(stringResource(R.string.pair)) {
                val found = LinkService.computers.values.sortedBy { it.name }
                if (found.isEmpty()) Text(stringResource(R.string.looking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (c in found) {
                    var code by remember(c.id) { mutableStateOf("") }
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it }, Modifier.weight(1f), singleLine = true,
                            label = { Text(stringResource(R.string.code)) })
                        Button(onClick = {
                            LinkService.pairingRequest = c to Protocol.normalCode(code)
                            LinkService.instance?.link?.close()
                        }, enabled = Protocol.normalCode(code).length == 8) { Text(stringResource(R.string.pair_button)) }
                    }
                }
            }
            Panel(stringResource(R.string.permissions)) {
                Text(stringResource(R.string.permissions_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text(stringResource(R.string.notification_access))
                }
                Button(onClick = {
                    ask.launch(buildList {
                        add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS); add(Manifest.permission.RECEIVE_SMS)
                        add(Manifest.permission.READ_PHONE_STATE); add(Manifest.permission.READ_CALL_LOG)
                        add(Manifest.permission.ANSWER_PHONE_CALLS); add(Manifest.permission.READ_CONTACTS)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }) { Text(stringResource(R.string.allow_sms_calls)) }
                // battery limit -> MIUI/OEMs kill the link in the background => ask once to lift it
                val power = getSystemService(android.os.PowerManager::class.java)
                if (power != null && !power.isIgnoringBatteryOptimizations(packageName)) {
                    Text(stringResource(R.string.background_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName")))
                        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text(stringResource(R.string.keep_running)) }
                }
            }
            Text(stringResource(R.string.share_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
