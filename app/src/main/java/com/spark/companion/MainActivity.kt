package com.spark.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 1. LAUNCHER ACTIVITY
class MainActivity : ComponentActivity() {

    private val PREFS_NAME = "CompanionPrefs"
    private val KEY_API_KEY = "gemini_api_key"

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureManager.init(this, result.resultCode, result.data!!)
            startForegroundService(Intent(this, FloatingCompanionService::class.java))
            finish()
        } else {
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""

        setContent {
            var apiKeyInput by remember { mutableStateOf(savedKey) }
            val deviceController = remember { DeviceController(this@MainActivity) }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "Spark Companion", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "AI buddy powered by Google AI Studio", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(28.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Google AI Studio API Key") },
                        placeholder = { Text("Paste your API key here") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val trimmed = apiKeyInput.trim()
                            if (trimmed.isEmpty()) {
                                Toast.makeText(this@MainActivity, "Please enter your API key", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            prefs.edit().putString(KEY_API_KEY, trimmed).apply()
                            CompanionBrainHolder.brain = CompanionBrain(trimmed, deviceController)
                            launchCompanion()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("Save & Spawn Companion")
                    }
                }
            }
        }
    }

    private fun launchCompanion() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }
}

// 2. COMPANION BRAIN
object CompanionBrainHolder {
    lateinit var brain: CompanionBrain
}

class CompanionBrain(apiKey: String, private val deviceController: DeviceController) {
    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey,
        systemInstruction = content {
            text(
                """
                You are Spark, an intelligent, playful desktop companion living on an Android device.
                Always prefix your response with an emotion tag: [HAPPY], [THINKING], [PROUD], [SURPRISED], or [SLEEPY].
                
                You can control phone hardware. When the user asks for:
                - Battery level: include [ACTION: BATTERY] in your response.
                - Turning on flashlight: include [ACTION: TORCH_ON] in your response.
                - Turning off flashlight: include [ACTION: TORCH_OFF] in your response.
                
                Keep your answers brief (1-2 sentences).
                """.trimIndent()
            )
        }
    )

    private val chat = model.startChat()

    suspend fun sendMessage(prompt: String): Pair<String, String> {
        return try {
            val response = chat.sendMessage(prompt)
            val fullText = response.text ?: "[HAPPY] I'm ready!"
            handleActionsAndParse(fullText)
        } catch (e: Exception) {
            Pair("CONFUSED", "Error: ${e.localizedMessage}")
        }
    }

    suspend fun analyzeScreen(bitmap: Bitmap, prompt: String = "What is on my screen?"): Pair<String, String> {
        return try {
            val response = model.generateContent(
                content {
                    image(bitmap)
                    text("[SURPRISED] $prompt Keep response under 2 sentences.")
                }
            )
            val fullText = response.text ?: "[HAPPY] Looking at it now!"
            handleActionsAndParse(fullText)
        } catch (e: Exception) {
            Pair("CONFUSED", "Could not analyze: ${e.localizedMessage}")
        }
    }

    private fun handleActionsAndParse(rawText: String): Pair<String, String> {
        var text = rawText
        if (text.contains("[ACTION: BATTERY]")) {
            val level = deviceController.getBatteryLevel()
            text = text.replace("[ACTION: BATTERY]", "(Battery: $level%)")
        }
        if (text.contains("[ACTION: TORCH_ON]")) {
            deviceController.setFlashlight(true)
            text = text.replace("[ACTION: TORCH_ON]", "")
        }
        if (text.contains("[ACTION: TORCH_OFF]")) {
            deviceController.setFlashlight(false)
            text = text.replace("[ACTION: TORCH_OFF]", "")
        }

        val emote = when {
            text.contains("[THINKING]") -> "THINKING"
            text.contains("[PROUD]") -> "PROUD"
            text.contains("[SURPRISED]") -> "SURPRISED"
            text.contains("[SLEEPY]") -> "SLEEPY"
            else -> "HAPPY"
        }
        val cleanText = text.replace(Regex("\\[.*?\\]"), "").trim()
        return Pair(emote, cleanText)
    }
}

// 3. HARDWARE CONTROLLER
class DeviceController(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
            if (enabled) "Flashlight turned ON" else "Flashlight turned OFF"
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }
}

// 4. SCREEN CAPTURE HELPER
object ScreenCaptureManager {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun init(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CompanionScreen",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureLatestFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}

// 5. ANIMATED AVATAR
@Composable
fun CompanionAvatar(emote: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isPressed by remember { mutableStateOf(false) }

    val scaleY by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "squish"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val eyeHeight by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3500
                12f at 0
                12f at 3300
                1f at 3400
                12f at 3500
            }
        ),
        label = "eyeBlink"
    )

    Canvas(
        modifier = modifier
            .size(90.dp)
            .clickable {
                isPressed = true
                onClick()
            }
    ) {
        val width = size.width
        val height = size.height * scaleY
        val top = size.height - height

        val bodyColor = when (emote) {
            "THINKING" -> Color(0xFF6750A4)
            "SURPRISED" -> Color(0xFFE26D5C)
            "PROUD" -> Color(0xFF388E3C)
            "CONFUSED" -> Color(0xFFE07A5F)
            else -> Color(0xFF2B2D42)
        }

        drawRoundRect(
            color = bodyColor,
            topLeft = Offset(0f, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(40f, 40f)
        )

        val eyeRadius = 6f
        val eyeY = top + (height * 0.42f)
        drawCircle(color = Color.White, radius = eyeRadius, center = Offset(width * 0.35f, eyeY))
        drawCircle(color = Color.White, radius = eyeRadius, center = Offset(width * 0.65f, eyeY))
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(120)
            isPressed = false
        }
    }
}

// 6. FLOATING SERVICE OVERLAY
class FloatingCompanionService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startForeground(101, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingCompanionService)
            setViewTreeSavedStateRegistryOwner(this@FloatingCompanionService)
            setViewTreeViewModelStoreOwner(this@FloatingCompanionService)

            setContent {
                var emote by remember { mutableStateOf("HAPPY") }
                var speechText by remember { mutableStateOf("Spark is active! Tap me.") }
                var showBubble by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            params.x += dragAmount.x.toInt()
                            params.y += dragAmount.y.toInt()
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                ) {
                    CompanionAvatar(
                        emote = emote,
                        onClick = { showBubble = !showBubble },
                        modifier = Modifier.size(75.dp)
                    )

                    if (showBubble) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF1E1E2E),
                            tonalElevation = 6.dp,
                            modifier = Modifier.widthIn(max = 240.dp).padding(4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = speechText, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        speechText = "Scanning screen..."
                                        emote = "THINKING"
                                        scope.launch(Dispatchers.IO) {
                                            val frame = ScreenCaptureManager.captureLatestFrame()
                                            if (frame != null) {
                                                val (newEmote, reply) = CompanionBrainHolder.brain.analyzeScreen(frame)
                                                speechText = reply
                                                emote = newEmote
                                            } else {
                                                speechText = "Couldn't grab screen."
                                                emote = "CONFUSED"
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Read Screen", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        floatView = composeView
        windowManager.addView(composeView, params)
    }

    private fun createNotification(): Notification {
        val channelId = "companion_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Companion Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Spark Companion Running")
            .setContentText("Tap avatar on screen to interact")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        floatView?.let { windowManager.removeView(it) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
