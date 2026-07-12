package org.goldenpass.androiduseh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.math.roundToInt

class UIAgentAccessibilityService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agent: IAgent? = null
    private var isProcessing by mutableStateOf(false)
    private lateinit var windowManager: WindowManager
    
    private var overlayComposeView: ComposeView? = null
    private var currentModelName by mutableStateOf("holo3-1-35b-a3b")
    private var conversationHistory = mutableStateListOf<ChatMessage>()
    private var isChatVisible by mutableStateOf(true)
    private var statusText by mutableStateOf("Ready")
    private var currentStepCount by mutableStateOf(0)

    private var currentStepCountInt = 0
    private var lastActionJson: String? = null
    private var repeatCount = 0
    private val MAX_STEPS = 30
    private val MAX_REPEATS = 7

    private var overlayX = 0f
    private var overlayY = 100f

    private var chatInputText by mutableStateOf("")

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val _viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        var instance: UIAgentAccessibilityService? = null
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        initializeAgent()
        showOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun initializeAgent() {
        val securityManager = SecurityManager(this)
        val apiKey = securityManager.getHoloApiKey()
        if (apiKey != null) {
            agent = HoloAgent(apiKey, currentModelName)
        } else {
            Toast.makeText(this, "Holo API Key is missing. Set it in the app.", Toast.LENGTH_LONG).show()
        }
    }

    fun startAgentLoop(taskDescription: String) {
        if (isProcessing) return
        if (agent == null) {
            initializeAgent()
            if (agent == null) return
        }

        hideChatOverlay()
        
        isProcessing = true
        currentStepCountInt = 0
        lastActionJson = null
        repeatCount = 0
        
        if (conversationHistory.none { it.isUser && it.text == taskDescription }) {
            conversationHistory.add(ChatMessage(taskDescription, true))
        }
        
        serviceScope.launch {
            processNextStep()
        }
    }

    private suspend fun processNextStep() {
        if (!isProcessing) return
        
        currentStepCountInt++
        if (currentStepCountInt > MAX_STEPS) {
            stopWithNotification("Task timed out: Maximum steps ($MAX_STEPS) reached.")
            return
        }

        updateOverlay(status = "Capturing screen...", step = currentStepCountInt)
        
        // Always capture full screen to match screen-absolute accessibility coordinates
        captureScreenshot(mainExecutor, -1) { bitmap ->
            if (bitmap == null) {
                stopWithNotification("Failed to capture screenshot")
                return@captureScreenshot
            }

            val softwareBitmap = try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                bitmap
            }

            val uiTree = getClickableElementsJson()
            
            serviceScope.launch {
                updateOverlay(status = "Thinking...", step = currentStepCountInt)
                val agentResponse = agent?.getNextAction(conversationHistory.toList(), softwareBitmap, uiTree)
                if (agentResponse != null) {
                    handleAgentAction(agentResponse)
                } else {
                    stopWithNotification("No response from Agent")
                }
            }
        }
    }

    private suspend fun handleAgentAction(jsonResponse: String) {
        val jsonStr = JsonUtils.extractJson(jsonResponse) ?: return
        try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action")
            val thought = json.optString("thought", "No reasoning provided")
            
            if (thought.isNotBlank()) {
                conversationHistory.add(ChatMessage(thought, false))
            }
            
            val actionContent = JSONObject(json.toString()).apply { remove("thought") }.toString()

            if (actionContent == lastActionJson) {
                repeatCount++
                if (repeatCount >= MAX_REPEATS) {
                    stopWithNotification("Agent is stuck in a loop.")
                    return
                }
            } else {
                repeatCount = 0
            }
            lastActionJson = actionContent

            when (action) {
                "click" -> {
                    val x = json.getDouble("x").toFloat()
                    val y = json.getDouble("y").toFloat()
                    updateOverlay("Clicking at (${x.toInt()}, ${y.toInt()})", currentStepCountInt)
                    showVisualCue(x, y, Color.RED)
                    delay(800)
                    performClickAt(x, y)
                    delay(2000)
                    processNextStep()
                }
                "type" -> {
                    val text = json.getString("text")
                    updateOverlay("Typing: $text", currentStepCountInt)
                    hideChatOverlay()
                    delay(800)
                    typeText(text)
                    delay(2000)
                    processNextStep()
                }
                "swipe" -> {
                    val startX = json.getDouble("startX").toFloat()
                    val startY = json.getDouble("startY").toFloat()
                    val endX = json.getDouble("endX").toFloat()
                    val endY = json.getDouble("endY").toFloat()
                    updateOverlay("Swiping...", currentStepCountInt)
                    showVisualCue(startX, startY, Color.GREEN)
                    delay(500)
                    showVisualCue(endX, endY, Color.YELLOW)
                    delay(300)
                    performSwipe(startX, startY, endX, endY)
                    delay(2000)
                    processNextStep()
                }
                "home" -> {
                    updateOverlay("Going Home", currentStepCountInt)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    delay(2000)
                    processNextStep()
                }
                "back" -> {
                    updateOverlay("Going Back", currentStepCountInt)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(2000)
                    processNextStep()
                }
                "done" -> {
                    stopWithNotification("Task Completed Successfully!")
                }
                else -> {
                    stopWithNotification("Unknown action received: $action")
                }
            }
        } catch (e: Exception) {
            stopWithNotification("Error parsing agent action")
        }
    }

    private fun showVisualCue(x: Float, y: Float, color: Int) {
        val size = 60
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(4, Color.WHITE)
            }
            alpha = 0.7f
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).toInt()
            this.y = (y - size / 2).toInt()
        }

        try {
            windowManager.addView(view, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {}
            }, 1000)
        } catch (e: Exception) {}
    }

    private fun typeText(text: String) {
        // Try to find the focused node in application windows first to avoid typing in our own overlay
        val windows = windows
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root ?: continue
                val focusedNode = findFocusedNode(root)
                if (focusedNode != null) {
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    focusedNode.recycle()
                    root.recycle()
                    return
                }
                root.recycle()
            }
        }

        // Fallback to rootInActiveWindow if no specific application window focused node found
        val rootNode = rootInActiveWindow ?: return
        if (rootNode.packageName == packageName) {
            rootNode.recycle()
            return
        }
        val focusedNode = findFocusedNode(rootNode)
        if (focusedNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()
        }
        rootNode.recycle()
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val focused = findFocusedNode(child)
            if (focused != null) return focused
            child.recycle()
        }
        return null
    }

    private fun captureScreenshot(executor: Executor, windowId: Int = -1, callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowId != -1) {
            takeScreenshotOfWindow(windowId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    callback(bitmap)
                }
                override fun onFailure(errorCode: Int) {
                    captureScreenshotLegacy(executor, callback)
                }
            })
        } else {
            captureScreenshotLegacy(executor, callback)
        }
    }

    private fun captureScreenshotLegacy(executor: Executor, callback: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                callback(bitmap)
            }
            override fun onFailure(errorCode: Int) {
                callback(null)
            }
        })
    }

    fun performClickAt(x: Float, y: Float) {
        serviceScope.launch {
            setOverlaysTouchable(false)
            delay(100)
            val clickPath = Path()
            clickPath.moveTo(x, y)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))
            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    setOverlaysTouchable(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    setOverlaysTouchable(true)
                }
            }, null)
        }
    }

    private fun setOverlaysTouchable(touchable: Boolean) {
        Handler(Looper.getMainLooper()).post {
            overlayComposeView?.let { view ->
                val params = view.layoutParams as WindowManager.LayoutParams
                if (touchable) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    private fun stopWithNotification(message: String) {
        isProcessing = false
        updateOverlay(status = message)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay() {
        if (overlayComposeView != null) return

        overlayComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@UIAgentAccessibilityService)
            setViewTreeViewModelStoreOwner(this@UIAgentAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@UIAgentAccessibilityService)
            
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
                        surface = androidx.compose.ui.graphics.Color(0xCC222222),
                        onSurface = androidx.compose.ui.graphics.Color.White
                    )
                ) {
                    OverlayContent()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayX.roundToInt()
            y = overlayY.roundToInt()
            width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        }

        windowManager.addView(overlayComposeView, params)
        updateWindowFlags()
    }

    @Composable
    fun OverlayContent() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                overlayX += dragAmount.x
                                overlayY += dragAmount.y
                                updateOverlayPosition()
                            }
                        }
                )
                Text(
                    text = "🤖 $currentModelName",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { toggleChat() }) {
                    Icon(Icons.Default.Chat, contentDescription = "Chat", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = "Step $currentStepCount/$MAX_STEPS",
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = {
                        if (isProcessing) {
                            stopWithNotification("Agent stopped by user.")
                        } else {
                            hideOverlay()
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = androidx.compose.ui.graphics.Color.Red)
                ) {
                    Icon(if (isProcessing) Icons.Default.Stop else Icons.Default.Close, contentDescription = "Stop", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (isChatVisible) {
                ChatContent()
            }
        }
    }

    @Composable
    fun ChatContent() {
        val scrollState = rememberScrollState()
        
        LaunchedEffect(conversationHistory.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                conversationHistory.takeLast(10).forEach { msg ->
                    Text(
                        text = (if (msg.isUser) "👤 " else "🤖 ") + msg.text,
                        color = if (msg.isUser) androidx.compose.ui.graphics.Color.Cyan else androidx.compose.ui.graphics.Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TextField(
                    value = chatInputText,
                    onValueChange = { chatInputText = it },
                    placeholder = { Text("Send instruction...", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = androidx.compose.ui.graphics.Color.White,
                        unfocusedTextColor = androidx.compose.ui.graphics.Color.White
                    )
                )
                IconButton(onClick = {
                    conversationHistory.clear()
                    chatInputText = ""
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = androidx.compose.ui.graphics.Color.Gray)
                }
                IconButton(onClick = {
                    if (chatInputText.isNotBlank()) {
                        val text = chatInputText
                        conversationHistory.add(ChatMessage(text, true))
                        chatInputText = ""
                        if (!isProcessing) {
                            startAgentLoop(text)
                        }
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun updateOverlayPosition() {
        overlayComposeView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.x = overlayX.roundToInt()
            params.y = overlayY.roundToInt()
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun hideOverlay() {
        isChatVisible = false
        overlayComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            overlayComposeView = null
        }
    }

    private fun hideChatOverlay() {
        isChatVisible = false
        updateWindowFlags()
    }

    private fun toggleChat() {
        isChatVisible = !isChatVisible
        updateWindowFlags()
    }

    private fun updateWindowFlags() {
        overlayComposeView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            if (isChatVisible) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            }
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun updateOverlay(status: String? = null, step: Int? = null) {
        Handler(Looper.getMainLooper()).post {
            status?.let { statusText = it }
            step?.let { currentStepCount = it }
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500L) {
        serviceScope.launch {
            setOverlaysTouchable(false)
            delay(100)
            val swipePath = Path()
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    setOverlaysTouchable(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    setOverlaysTouchable(true)
                }
            }, null)
        }
    }

    fun getClickableElementsJson(): String {
        val clickableItems = JSONArray()
        val windows = windows
        
        // Sort windows by layer to handle overlapping windows correctly (highest layer last)
        val sortedWindows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            windows.sortedBy { it.layer }
        } else {
            windows
        }

        for (window in sortedWindows) {
            val rootNode = window.root ?: continue
            
            // Skip our own overlay to prevent the agent from trying to click its own UI
            if (rootNode.packageName == packageName) {
                rootNode.recycle()
                continue
            }

            traverseAndCollectClickable(rootNode, clickableItems)
            rootNode.recycle()
        }

        return clickableItems.toString()
    }

    private fun traverseAndCollectClickable(node: AccessibilityNodeInfo?, items: JSONArray) {
        if (node == null) return
        if (node.isClickable) {
            val item = JSONObject()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            item.put("text", node.text?.toString() ?: "")
            item.put("contentDescription", node.contentDescription?.toString() ?: "")
            item.put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            items.put(item)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseAndCollectClickable(child, items)
            child?.recycle()
        }
    }
}
