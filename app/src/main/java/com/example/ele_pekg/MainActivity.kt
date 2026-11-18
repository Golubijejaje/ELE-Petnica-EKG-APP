package com.example.ele_pekg

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: EcgBleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = EcgBleManager(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EcgScreen(bleManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@Composable
fun EcgScreen(bleManager: EcgBleManager) {
    val connectionState by bleManager.connectionState.collectAsState()
    var autoScale by remember { mutableStateOf(false) }

    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connectionState) {
        while (true) {
            withFrameNanos { time ->
                frameTick = time
            }
        }
    }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            bleManager.startScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Gornji meni
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ELE-P EKG MONITOR",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF58A6FF),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Status: ${connectionState.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectionState == ConnectionState.CONNECTED) Color(0xFF3FB950) else Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { autoScale = !autoScale },
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (autoScale) Color(0xFF1F6FEB) else Color(0xFF21262D)
                    )
                ) {
                    Text(
                        text = if (autoScale) "Auto-Gain" else "Fixed 0-3.3V",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }

                Button(
                    onClick = {
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            permissionLauncher.launch(permissions)
                        } else {
                            bleManager.disconnect()
                        }
                    },
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connectionState == ConnectionState.DISCONNECTED) Color(0xFF238636) else Color(0xFFDA3633)
                    )
                ) {
                    Text(
                        text = if (connectionState == ConnectionState.DISCONNECTED) "Connect" else "Disconnect",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Graf sa zaobljenim uglovima (Material Design)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)) // Zaobljeni uglovi prozora grafa
                .background(Color(0xFF05080C))
        ) {
            EcgWaveformCanvas(
                bleManager = bleManager,
                frameTick = frameTick,
                autoScale = autoScale
            )
        }
    }
}

@Composable
fun EcgWaveformCanvas(
    bleManager: EcgBleManager,
    frameTick: Long,
    autoScale: Boolean
) {
    val pathBeforeGap = remember { Path() }
    val pathAfterGap = remember { Path() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val fullWidth = size.width
        val fullHeight = size.height

        val leftPadding = 80f
        val rightPadding = 40f
        val topPadding = 25f
        val bottomPadding = 45f

        val width = fullWidth - leftPadding - rightPadding
        val height = fullHeight - topPadding - bottomPadding

        val buffer = bleManager.sampleBuffer
        val count = bleManager.bufferSize
        val head = bleManager.writeIndex

        // 1. Iscrtavanje EKG Mreže
        val minorStep = width / 40f
        val gridColorMinor = Color(0xFF132218)
        val gridColorMajor = Color(0xFF1F3D29)

        var x = leftPadding
        var col = 0
        while (x <= fullWidth - rightPadding + 1f) {
            drawLine(
                color = if (col % 5 == 0) gridColorMajor else gridColorMinor,
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + height),
                strokeWidth = if (col % 5 == 0) 1.5f else 1f
            )
            x += minorStep
            col++
        }

        var y = topPadding
        var row = 0
        val rowStep = height / 10f
        while (y <= topPadding + height + 1f) {
            drawLine(
                color = if (row % 2 == 0) gridColorMajor else gridColorMinor,
                start = Offset(leftPadding, y),
                end = Offset(fullWidth - rightPadding, y),
                strokeWidth = if (row % 2 == 0) 1.5f else 1f
            )
            y += rowStep
            row++
        }

        // 2. Opseg vrednosti (Y osa)
        var minVal = 0f
        var maxVal = 4095f
        if (autoScale) {
            var localMin = 4095f
            var localMax = 0f
            for (i in 0 until count) {
                val v = buffer[i]
                if (v < localMin) localMin = v
                if (v > localMax) localMax = v
            }
            val span = (localMax - localMin).coerceAtLeast(100f)
            minVal = (localMin - span * 0.15f).coerceAtLeast(0f)
            maxVal = (localMax + span * 0.15f).coerceAtMost(4095f)
        }
        val range = (maxVal - minVal).coerceAtLeast(1f)

        // 3. Iscrtavanje Y ose (Volt-skala)
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#8B949E")
            textSize = 24f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val stepsY = 4
        for (i in 0..stepsY) {
            val fraction = i / stepsY.toFloat()
            if (i == stepsY) continue // Preskačemo donju nulu

            val voltage = if (autoScale) {
                maxVal - fraction * range
            } else {
                3.3f - fraction * 3.3f
            }
            val posY = topPadding + fraction * height + 8f
            nativeCanvas.drawText(String.format("%.2fV", voltage), 12f, posY, textPaint)
        }

        // 4. Iscrtavanje X ose (Vremenska skala)
        val timePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#8B949E")
            textSize = 22f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val totalSeconds = 4f
        val stepsX = 4
        for (i in 0..stepsX) {
            val fraction = i / stepsX.toFloat()
            val timeSec = fraction * totalSeconds
            val posX = leftPadding + fraction * width - 15f
            nativeCanvas.drawText(String.format("%.1fs", timeSec), posX, fullHeight - 14f, timePaint)
        }

        // 5. Iscrtavanje EKG talasnog oblika
        pathBeforeGap.reset()
        pathAfterGap.reset()
        val gapSamples = 60
        val stepX = width / count.toFloat()

        var startedBefore = false
        for (i in 0 until head) {
            val px = leftPadding + (i * stepX)
            val normalized = ((buffer[i] - minVal) / range).coerceIn(0f, 1f)
            val py = topPadding + height - (normalized * height)
            if (!startedBefore) {
                pathBeforeGap.moveTo(px, py)
                startedBefore = true
            } else {
                pathBeforeGap.lineTo(px, py)
            }
        }

        var startedAfter = false
        for (i in (head + gapSamples) until count) {
            val px = leftPadding + (i * stepX)
            val normalized = ((buffer[i] - minVal) / range).coerceIn(0f, 1f)
            val py = topPadding + height - (normalized * height)
            if (!startedAfter) {
                pathAfterGap.moveTo(px, py)
                startedAfter = true
            } else {
                pathAfterGap.lineTo(px, py)
            }
        }

        val traceStroke = Stroke(
            width = 3.5f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val traceColor = Color(0xFF00FF66)

        drawPath(pathBeforeGap, color = traceColor, style = traceStroke)
        drawPath(pathAfterGap, color = traceColor, style = traceStroke)

        val headX = leftPadding + (head * stepX)
        val lastIdx = if (head == 0) count - 1 else head - 1
        val headNorm = ((buffer[lastIdx] - minVal) / range).coerceIn(0f, 1f)
        val headY = topPadding + height - (headNorm * height)
        drawCircle(color = Color.White, radius = 5f, center = Offset(headX, headY))
    }
}