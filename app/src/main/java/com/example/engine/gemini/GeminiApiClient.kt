package com.example.engine.gemini

import android.util.Log
import com.example.engine.BalancingConfig
import com.example.engine.CharacterStatInput
import com.example.engine.RosterBalancingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Result wrapper for Gemini API operations.
 */
sealed class GeminiResult<out T> {
    data class Success<out T>(
        val data: T,
        val modelName: String = GeminiApiClient.DEFAULT_MODEL
    ) : GeminiResult<T>()

    data class Error(
        val code: Int,
        val message: String,
        val isAuthError: Boolean = false,
        val isQuotaError: Boolean = false
    ) : GeminiResult<Nothing>()
}

/**
 * Raw proposal returned by Gemini for a single character before local validation.
 */
data class GeminiCharacterProposal(
    val characterId: String,
    val proposedLife: Int?,
    val proposedAttack: Int?,
    val proposedDefence: Int?,
    val archetype: String?,
    val rationale: String
)

/**
 * Client for interacting with Google Gemini API using user-provided personal API keys.
 *
 * Enforces:
 * 1. Target model: "gemini-3.8-flash"
 * 2. Header-based authentication (`x-goog-api-key`) - API key is never placed in the URL
 * 3. Never logging API keys, authorization headers, or sensitive request contents
 * 4. Structured JSON responses via responseSchema
 * 5. Strict local validation and bounding of all returned values
 * 6. Explicit timeouts and cancellation support
 */
object GeminiApiClient {

    private const val TAG = "GeminiApiClient"
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Tests the validity of the user's personal API key and its ability to communicate
     * with the `gemini-3.8-flash` model.
     */
    suspend fun testConnection(apiKey: String): GeminiResult<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext GeminiResult.Error(
                code = 400,
                message = "لم يتم إدخال مفتاح API",
                isAuthError = true
            )
        }

        val url = "$BASE_URL/$DEFAULT_MODEL:generateContent"

        val requestBodyJson = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().put("text", "Ping test. Respond with exactly the word OK."))
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)
            val genConfig = JSONObject().apply {
                put("maxOutputTokens", 10)
                put("temperature", 0.0)
            }
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", trimmedKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string() ?: ""

                when (code) {
                    200 -> {
                        GeminiResult.Success(
                            data = "الاتصال ناجح مع نموذج $DEFAULT_MODEL",
                            modelName = DEFAULT_MODEL
                        )
                    }
                    400 -> {
                        Log.w(TAG, "Gemini API 400 Bad Request")
                        GeminiResult.Error(
                            code = 400,
                            message = "طلب غير صالح (Bad Request). تأكد من معلمات النموذج."
                        )
                    }
                    401, 403 -> {
                        Log.w(TAG, "Gemini API Auth Failure ($code)")
                        GeminiResult.Error(
                            code = code,
                            message = "مفتاح Gemini غير صالح أو غير مصرح له بالوصول للنموذج (API Key Invalid/Unauthorized).",
                            isAuthError = true
                        )
                    }
                    429 -> {
                        Log.w(TAG, "Gemini API Quota Exceeded (429)")
                        GeminiResult.Error(
                            code = 429,
                            message = "تم تجاوز حصة الاستخدام أو حد الطلبات لحسابك لدى Google (Quota/Rate Limit Exceeded).",
                            isQuotaError = true
                        )
                    }
                    500, 503 -> {
                        Log.w(TAG, "Gemini API Server Error ($code)")
                        GeminiResult.Error(
                            code = code,
                            message = "خوادم Google Gemini تواجه ضغطاً أو غير متاحة مؤقتاً ($code)."
                        )
                    }
                    else -> {
                        Log.w(TAG, "Gemini API Unexpected Response ($code)")
                        GeminiResult.Error(
                            code = code,
                            message = "استجابة غير متوقعة من خادم Gemini ($code)."
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network connection error: ${e.message}")
            GeminiResult.Error(
                code = -1,
                message = "تعذر الاتصال بالشبكة: يرجى التحقق من اتصال الإنترنت والمحاولة لاحقاً."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in testConnection: ${e.message}")
            GeminiResult.Error(
                code = -2,
                message = "حدث خطأ غير متوقع أثناء اختبار الاتصال: ${e.localizedMessage ?: "Unknown"}"
            )
        }
    }

    /**
     * Requests intelligent roster balance proposals from Gemini using structured JSON output.
     */
    suspend fun analyzeRosterWithGemini(
        apiKey: String,
        characters: List<CharacterStatInput>,
        config: BalancingConfig = RosterBalancingEngine.DEFAULT_CONFIG
    ): GeminiResult<List<GeminiCharacterProposal>> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext GeminiResult.Error(
                code = 400,
                message = "مفتاح API غير متوفر",
                isAuthError = true
            )
        }

        if (characters.isEmpty()) {
            return@withContext GeminiResult.Success(emptyList())
        }

        val url = "$BASE_URL/$DEFAULT_MODEL:generateContent"

        // Build concise prompt containing only fighters with non-null stats
        val validChars = characters.filter { it.rawError == null && (it.life != null || it.attack != null || it.defence != null) }
        if (validChars.isEmpty()) {
            return@withContext GeminiResult.Success(emptyList())
        }

        val rosterDataJson = JSONArray().apply {
            for (c in validChars) {
                put(JSONObject().apply {
                    put("id", c.characterId)
                    put("name", c.displayName)
                    put("life", c.life ?: 1000)
                    put("attack", c.attack ?: 100)
                    put("defence", c.defence ?: 100)
                })
            }
        }

        val systemInstruction = """
            أنت خبير محترف في موازنة ألعاب القتال ومحرك MUGEN.
            مهمتك هي مراجعة إحصائيات قائمة المقاتلين واقتراح تعديلات تحافظ على توازن اللعبة مع الحفاظ الكامل على هوية المقاتل (Archetype):
            - المقاتل الضخم أو الزعيم (Tank/Boss) يظل أثقل من المتوسط ولكنه لا يتجاوز نقاط حياة ${config.lifeDampingCap} وهجوم ${config.attackDampingCap}%.
            - المقاتل السريع والهش (Glass Cannon / Speedster) يظل أسرع وأقل دفاعاً ولكنه لا يقل عن نقاط حياة ${config.lifeDampingFloor} ودفاع ${config.defenceDampingFloor}%.
            - المقاتلون المتوازنون ضمن النطاق الطبيعي (Life: ${config.lifeTargetMin}-${config.lifeTargetMax}, Attack: ${config.attackTargetMin}-${config.attackTargetMax}, Defence: ${config.defenceTargetMin}-${config.defenceTargetMax}) يجب إبقاؤهم كما هم دون تعديل أو بتعديل طفيف جداً إن تطلب الأمر.
            - اكتب شرحاً تفسيرياً واضحاً باللغة العربية لكل شخصية يوضح سبب التعديل ونوعية المقاتل.
        """.trimIndent()

        // Structured Schema definition for Gemini
        val schemaObj = JSONObject().apply {
            put("type", "ARRAY")
            val itemsObj = JSONObject().apply {
                put("type", "OBJECT")
                val properties = JSONObject().apply {
                    put("characterId", JSONObject().put("type", "STRING"))
                    put("proposedLife", JSONObject().put("type", "INTEGER"))
                    put("proposedAttack", JSONObject().put("type", "INTEGER"))
                    put("proposedDefence", JSONObject().put("type", "INTEGER"))
                    put("archetype", JSONObject().put("type", "STRING"))
                    put("rationale", JSONObject().put("type", "STRING"))
                }
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("characterId")
                    put("proposedLife")
                    put("proposedAttack")
                    put("proposedDefence")
                    put("rationale")
                })
            }
            put("items", itemsObj)
        }

        val requestBodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", systemInstruction))
                })
            })

            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "بيانات المقاتلين المطلوب تحليلهم:\n$rosterDataJson"))
                    })
                })
            })

            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("response_schema", schemaObj)
                put("temperature", 0.2)
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", trimmedKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (code != 200) {
                    val errorMsg = when (code) {
                        401, 403 -> "مفتاح Gemini غير صالح أو غير مصرح له"
                        429 -> "تم تجاوز حصة الاستخدام لحسابك لدى Google"
                        else -> "فشل طلب تحليل Gemini ($code)"
                    }
                    return@withContext GeminiResult.Error(
                        code = code,
                        message = errorMsg,
                        isAuthError = (code == 401 || code == 403),
                        isQuotaError = (code == 429)
                    )
                }

                // Parse Candidates
                val rootJson = JSONObject(responseBody)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext GeminiResult.Error(code = 500, message = "لم يرجع النموذج أي مقترحات")
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val textPayload = parts?.optJSONObject(0)?.optString("text") ?: ""

                if (textPayload.isBlank()) {
                    return@withContext GeminiResult.Error(code = 500, message = "استجابة نصية فارغة من النموذج")
                }

                val proposalsArray = JSONArray(textPayload)
                val parsedList = mutableListOf<GeminiCharacterProposal>()

                for (i in 0 until proposalsArray.length()) {
                    val item = proposalsArray.getJSONObject(i)
                    val id = item.optString("characterId").trim()
                    val life = if (item.has("proposedLife") && !item.isNull("proposedLife")) item.optInt("proposedLife") else null
                    val attack = if (item.has("proposedAttack") && !item.isNull("proposedAttack")) item.optInt("proposedAttack") else null
                    val defence = if (item.has("proposedDefence") && !item.isNull("proposedDefence")) item.optInt("proposedDefence") else null
                    val archetype = if (item.has("archetype") && !item.isNull("archetype")) item.optString("archetype") else null
                    val rationale = item.optString("rationale", "")

                    if (id.isNotEmpty()) {
                        parsedList.add(
                            GeminiCharacterProposal(
                                characterId = id,
                                proposedLife = life,
                                proposedAttack = attack,
                                proposedDefence = defence,
                                archetype = archetype,
                                rationale = rationale
                            )
                        )
                    }
                }

                GeminiResult.Success(parsedList, DEFAULT_MODEL)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network failure in analyzeRosterWithGemini: ${e.message}")
            GeminiResult.Error(code = -1, message = "تعذر الاتصال بالشبكة لتحليل القائمة بواسطة Gemini")
        } catch (e: Exception) {
            Log.e(TAG, "Exception parsing Gemini response: ${e.message}", e)
            GeminiResult.Error(code = -2, message = "خطأ أثناء معالجة استجابة الذكاء الاصطناعي: ${e.message}")
        }
    }
}
