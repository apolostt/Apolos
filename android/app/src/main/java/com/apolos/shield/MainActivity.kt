package com.apolos.shield

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apolos.shield.core.AppRisk
import com.apolos.shield.core.SecurityEvent
import com.apolos.shield.core.SecurityState
import com.apolos.shield.core.Severity
import com.apolos.shield.service.MonitoringService
import com.apolos.shield.ui.theme.ApolosTheme
import com.apolos.shield.ui.theme.ShieldAmber
import com.apolos.shield.ui.theme.ShieldGreen
import com.apolos.shield.ui.theme.ShieldRed
import com.apolos.shield.vpn.ShieldVpnService
import com.apolos.shield.vpn.WireGuardConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApolosTheme { Dashboard() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Dashboard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val status by SecurityState.status.collectAsStateWithLifecycle()
    val events by SecurityState.events.collectAsStateWithLifecycle()
    val apps by SecurityState.apps.collectAsStateWithLifecycle()
    val alarm by SecurityState.alarm.collectAsStateWithLifecycle()

    var pendingVpnMode by remember { mutableStateOf<String?>(null) }
    var pendingWgConf by remember { mutableStateOf<String?>(null) }
    var showWireGuard by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val conf = pendingWgConf
            val mode = pendingVpnMode
            when {
                conf != null -> WireGuardConnection.connect(ctx, conf)
                mode != null -> ShieldVpnService.start(ctx, mode)
            }
        }
        pendingVpnMode = null
        pendingWgConf = null
    }

    fun launchVpn(mode: String) {
        val prepare = VpnService.prepare(ctx)
        if (prepare != null) {
            pendingVpnMode = mode
            pendingWgConf = null
            vpnPrepareLauncher.launch(prepare)
        } else {
            ShieldVpnService.start(ctx, mode)
        }
    }

    fun connectWireGuard(conf: String) {
        val prepare = VpnService.prepare(ctx)
        if (prepare != null) {
            pendingWgConf = conf
            pendingVpnMode = null
            vpnPrepareLauncher.launch(prepare)
        } else {
            WireGuardConnection.connect(ctx, conf)
        }
    }

    val threatColor = when (status.threat) {
        Severity.CRITICAL -> ShieldRed
        Severity.WARNING -> ShieldAmber
        Severity.INFO -> ShieldGreen
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, null, tint = threatColor)
                    Text(
                        stringRes(ctx, R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            // ---- Alarm banner ----
            alarm?.let { a ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ShieldRed),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = Color.White)
                                Text(
                                    "  ${a.title}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(a.detail, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { SecurityState.clearAlarm() }) {
                                Text(stringRes(ctx, R.string.dismiss), color = Color.White)
                            }
                        }
                    }
                }
            }

            // ---- Threat header ----
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = threatColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            stringRes(ctx, R.string.threat_level),
                            color = Color.White,
                        )
                        Text(
                            when (status.threat) {
                                Severity.CRITICAL -> stringRes(ctx, R.string.threat_critical)
                                Severity.WARNING -> stringRes(ctx, R.string.threat_warning)
                                Severity.INFO -> stringRes(ctx, R.string.threat_ok)
                            },
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // ---- Live sensor chips ----
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensorChip(Icons.Filled.CameraAlt, stringRes(ctx, R.string.chip_camera), status.cameraInUse)
                    SensorChip(Icons.Filled.Mic, stringRes(ctx, R.string.chip_mic), status.micInUse)
                    SensorChip(Icons.Filled.ScreenShare, stringRes(ctx, R.string.chip_screen), status.screenCaptured)
                }
            }

            // ---- Status summary ----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusLine(stringRes(ctx, R.string.st_monitoring), boolText(ctx, status.monitoringActive))
                        StatusLine(stringRes(ctx, R.string.st_vpn), if (status.vpnActive) status.vpnMode else boolText(ctx, false))
                        StatusLine(stringRes(ctx, R.string.st_blocked), status.blockedDnsCount.toString())
                        StatusLine(stringRes(ctx, R.string.st_flagged), status.flaggedApps.toString())
                    }
                }
            }

            // ---- Controls ----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringRes(ctx, R.string.controls), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { MonitoringService.start(ctx) }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.start_protection))
                            }
                            OutlinedButton(onClick = { MonitoringService.stop(ctx) }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.stop))
                            }
                        }
                        Text(stringRes(ctx, R.string.vpn_section), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { launchVpn(ShieldVpnService.MODE_DNS) }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.vpn_dns))
                            }
                            FilledTonalButton(onClick = { launchVpn(ShieldVpnService.MODE_KILLSWITCH) }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.vpn_kill))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showWireGuard = true }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.vpn_wireguard))
                            }
                            OutlinedButton(onClick = { ShieldVpnService.stop(ctx); WireGuardConnection.disconnect(ctx) }, modifier = Modifier.weight(1f)) {
                                Text(stringRes(ctx, R.string.vpn_off))
                            }
                        }
                    }
                }
            }

            // ---- Permissions ----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringRes(ctx, R.string.permissions), fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringRes(ctx, R.string.grant_notifications)) }
                        OutlinedButton(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringRes(ctx, R.string.grant_usage)) }
                    }
                }
            }

            // ---- Flagged apps ----
            if (apps.isNotEmpty()) {
                item { SectionTitle(stringRes(ctx, R.string.flagged_apps)) }
                items(apps.take(20), key = { it.packageName }) { AppRow(it) }
            }

            // ---- Recent events ----
            item { SectionTitle(stringRes(ctx, R.string.recent_events)) }
            items(events.take(40), key = { it.id }) { EventRow(it) }
        }
    }

    if (showWireGuard) {
        WireGuardDialog(
            onDismiss = { showWireGuard = false },
            onConnect = { conf ->
                showWireGuard = false
                connectWireGuard(conf)
            },
        )
    }
}

@Composable
private fun SensorChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) ShieldRed else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AppRow(app: AppRisk) {
    val color = when {
        app.riskScore >= 70 -> ShieldRed
        app.riskScore >= 50 -> ShieldAmber
        else -> ShieldGreen
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(app.label, fontWeight = FontWeight.Bold)
                Text(app.riskScore.toString(), color = color, fontWeight = FontWeight.Bold)
            }
            Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(app.reasons.joinToString(", "), fontSize = 12.sp)
        }
    }
}

@Composable
private fun EventRow(e: SecurityEvent) {
    val color = when (e.severity) {
        Severity.CRITICAL -> ShieldRed
        Severity.WARNING -> ShieldAmber
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(e.title, fontWeight = FontWeight.Bold, color = color)
            Text(timeFmt.format(Date(e.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(e.detail, fontSize = 13.sp)
    }
}

@Composable
private fun WireGuardDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes(ctx, R.string.wg_title)) },
        text = {
            Column {
                Text(stringRes(ctx, R.string.wg_hint), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = ...\n[Peer]\nPublicKey = ...\nEndpoint = ...:51820\nAllowedIPs = 0.0.0.0/0") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConnect(text) }) {
                Text(stringRes(ctx, R.string.connect))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes(ctx, R.string.cancel)) } },
    )
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun boolText(ctx: android.content.Context, v: Boolean): String =
    stringRes(ctx, if (v) R.string.on else R.string.off)

private fun stringRes(ctx: android.content.Context, id: Int): String = ctx.getString(id)
