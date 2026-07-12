package org.goldenpass.androiduseh

import android.graphics.Bitmap
import android.util.Log
import com.aallam.openai.api.chat.ChatMessage as OpenAIChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.chat.chatCompletionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

class HoloAgent(apiKey: String, private val modelName: String = "holo3-1-35b-a3b") : IAgent {
    private val config = OpenAIConfig(
        token = apiKey,
        host = OpenAIHost("https://api.hcompany.ai/v1/"),
        timeout = Timeout(request = 60.seconds)
    )
    private val openai = OpenAI(config)

    override suspend fun getNextAction(history: List<ChatMessage>, screenshot: Bitmap, uiTree: String): String? = withContext(Dispatchers.IO) {
        val screenWidth = screenshot.width
        val screenHeight = screenshot.height

        val normalizedUiTree = normalizeUiTree(uiTree, screenWidth, screenHeight)

        val resizedScreenshot = BitmapUtils.resizeBitmap(screenshot, 1024)
        val base64Image = BitmapUtils.bitmapToBase64(resizedScreenshot)
        if (resizedScreenshot != screenshot) {
            resizedScreenshot.recycle()
        }

        val systemInstructions = """
            You are an expert Android UI Automation Agent.
            Your goal is to complete a user-specified TASK by analyzing a screenshot and a UI Tree.
            
            COORDINATE SYSTEM:
            - All coordinates (x, y, startX, startY, endX, endY) MUST be in normalized 0-1000 format.
            - (0, 0) is the top-left corner.
            - (1000, 1000) is the bottom-right corner.
            
            UI TREE DATA:
            - The UI Tree contains clickable elements and their normalized center coordinates. Use this to help locate precise targets.
            
            REQUIRED RESPONSE FORMAT (JSON ONLY):
            You must respond with a SINGLE JSON object in one of these formats.
            COORDINATES MUST BE NUMBERS, NOT STRINGS.
            
            1. CLICK ACTION:
            {
              "thought": "Reasoning for the action.",
              "action": "click",
              "x": 500,
              "y": 500
            }
            
            2. TYPE ACTION (Use this after clicking/focusing an input field):
            {
              "thought": "Reasoning for the action.",
              "action": "type",
              "text": "text to type"
            }
            
            3. SWIPE ACTION:
            {
              "thought": "Reasoning for the action.",
              "action": "swipe",
              "startX": 500,
              "startY": 800,
              "endX": 500,
              "endY": 200
            }

            4. HOME ACTION (Go to the home screen):
            {
              "thought": "Going home to find the app.",
              "action": "home"
            }

            5. BACK ACTION (Go back):
            {
              "thought": "Going back.",
              "action": "back"
            }
            
            6. DONE:
            {
              "thought": "Task is complete.",
              "action": "done"
            }
            
            IMPORTANT RULES:
            - Respond ONLY with the JSON object. No other text.
            - Keep the "thought" field brief and concise (max 2 sentences).
            - Be precise with coordinates.
            - If the task is finished, return the "done" action.
        """.trimIndent()

        val historyStr = history.joinToString("\n") { 
            if (it.isUser) "USER: ${it.text}" else "AI: ${it.text}"
        }

        val userPrompt = """
            CONVERSATION HISTORY:
            $historyStr
            
            CURRENT UI TREE (Normalized Centers):
            $normalizedUiTree
            
            Based on the history and the current screen, what is the NEXT action?
        """.trimIndent()

        try {
            val chatCompletionRequest = chatCompletionRequest {
                model = ModelId(modelName)
                messages {
                    message {
                        role = ChatRole.System
                        content = systemInstructions
                    }
                    message {
                        role = ChatRole.User
                        content {
                            text(userPrompt)
                            image("data:image/jpeg;base64,$base64Image")
                        }
                    }
                }
                maxTokens = 8192
            }
            val completion = openai.chatCompletion(chatCompletionRequest)
            val rawResult = completion.choices.firstOrNull()?.message?.content?.trim()
            Log.d("HoloAgent", "REQUEST SEND TO LLM (Model: $modelName)")
            Log.d("HoloAgent", "RAW RESPONSE: $rawResult")

            return@withContext denormalizeResponse(rawResult, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e("HoloAgent", "API Error: ${e.message}", e)
            return@withContext null
        }
    }

    private fun normalizeUiTree(uiTree: String, screenWidth: Int, screenHeight: Int): String {
        try {
            val originalArray = JSONArray(uiTree)
            val normalizedArray = JSONArray()
            for (i in 0 until originalArray.length()) {
                val item = originalArray.getJSONObject(i)
                val boundsStr = item.optString("bounds", "")
                
                if (boundsStr.isNotEmpty()) {
                    val regex = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
                    val match = regex.find(boundsStr)
                    if (match != null) {
                        val left = match.groupValues[1].toInt()
                        val top = match.groupValues[2].toInt()
                        val right = match.groupValues[3].toInt()
                        val bottom = match.groupValues[4].toInt()
                        
                        val centerX = (left + right) / 2
                        val centerY = (top + bottom) / 2
                        
                        val nx = (centerX * 1000 / screenWidth).coerceIn(0, 1000)
                        val ny = (centerY * 1000 / screenHeight).coerceIn(0, 1000)
                        
                        val normalizedItem = JSONObject()
                        normalizedItem.put("text", item.optString("text"))
                        normalizedItem.put("contentDescription", item.optString("contentDescription"))
                        normalizedItem.put("center", "($nx, $ny)")
                        normalizedArray.put(normalizedItem)
                    }
                }
            }
            return normalizedArray.toString()
        } catch (e: Exception) {
            Log.e("HoloAgent", "Error normalizing UI tree", e)
            return uiTree
        }
    }

    private fun parseCoordinate(json: JSONObject, key: String): Double {
        val value = json.opt(key) ?: return -1.0
        if (value is Number) return value.toDouble()
        if (value is String) {
            // Handle strings like "483" or "483," or " 483 "
            return value.trim().removeSuffix(",").toDoubleOrNull() ?: -1.0
        }
        return -1.0
    }

    private fun denormalizeResponse(rawResponse: String?, screenWidth: Int, screenHeight: Int): String? {
        if (rawResponse == null) return null
        val jsonStr = JsonUtils.extractJson(rawResponse) ?: return null
        try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action")
            
            if (action == "click") {
                val nx = parseCoordinate(json, "x")
                val ny = parseCoordinate(json, "y")
                if (nx >= 0 && ny >= 0) {
                    val x = (nx / 1000.0 * screenWidth).toInt()
                    val y = (ny / 1000.0 * screenHeight).toInt()
                    json.put("x", x)
                    json.put("y", y)
                }
            } else if (action == "swipe") {
                val nsx = parseCoordinate(json, "startX")
                val nsy = parseCoordinate(json, "startY")
                val nex = parseCoordinate(json, "endX")
                val ney = parseCoordinate(json, "endY")
                
                if (nsx >= 0 && nsy >= 0 && nex >= 0 && ney >= 0) {
                    json.put("startX", (nsx / 1000.0 * screenWidth).toInt())
                    json.put("startY", (nsy / 1000.0 * screenHeight).toInt())
                    json.put("endX", (nex / 1000.0 * screenWidth).toInt())
                    json.put("endY", (ney / 1000.0 * screenHeight).toInt())
                }
            }
            return json.toString()
        } catch (e: Exception) {
            Log.e("HoloAgent", "Error denormalizing response: ${e.message}", e)
            return null
        }
    }
}
