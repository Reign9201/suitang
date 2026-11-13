package com.reign.bilistudy.net

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoice
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolChoiceTool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reign.bilistudy.core.AIJudgement
import com.reign.bilistudy.core.AppException
import com.reign.bilistudy.core.Classify
import com.reign.bilistudy.core.Course
import com.reign.bilistudy.core.Prompts
import com.reign.bilistudy.core.SearchIntent
import com.reign.bilistudy.core.Taxonomy
import com.reign.bilistudy.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 大模型设置，字段和 Python 原型 config.json 的「ai」一节、iOS AIConfig 对应。密钥在 SecureStore。 */
data class AIConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "https://api.anthropic.com",
    val auth: String = "apiKey",          // apiKey / bearer
    val organizeModel: String = "claude-opus-5-5",
    val intentModel: String = "claude-opus-5-5",
    val effort: String = "low",           // "" 表示请求里不带
    val jsonMode: String = "auto",        // auto / schema / tool / prompt
    val apiVersion: String = "",          // "" 表示自动：官方地址用 2026-10-21，其他地址用 2023-06-01
    val batchSize: Int = 10,
    val concurrency: Int = 4,
    val timeoutSeconds: Int = 120,
) {
    val isOfficial get() = baseUrl.contains("api.anthropic.com")
    val effectiveJsonMode get() = if (jsonMode == "auto") (if (isOfficial) "schema" else "tool") else jsonMode
    val effectiveApiVersion get() = apiVersion.ifEmpty { if (isOfficial) "2026-10-21" else "2023-06-01" }

    /** 没启用、没有密钥时返回原因；可以用时返回 null。 */
    val unavailableReason: String? get() = when {
        !enabled -> "设置里关闭了大模型整理"
        secret.isNullOrEmpty() -> "没有填写大模型密钥"
        else -> null
    }

    companion object {
        private val gson = Gson()
        private lateinit var context: Context
        fun init(c: Context) { context = c.applicationContext }
        private val prefs get() = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
        fun load(): AIConfig = runCatching { gson.fromJson(prefs.getString("config", null), AIConfig::class.java) }.getOrNull() ?: AIConfig()
        fun save(c: AIConfig) = prefs.edit().putString("config", gson.toJson(c)).apply()
        var secret: String?
            get() = SecureStore.get("ai.secret")
            set(v) = SecureStore.set("ai.secret", v)
    }
}

/**
 * 调 Anthropic Messages 接口（或兼容它的网关，如智谱），用官方 Java SDK。
 * 逻辑和原型 ai.py、iOS AIClient 一致：分批并行整理、三种拿 JSON 的方式、结果校验。
 */
class AIClient(private val config: AIConfig) {
    companion object {
        private const val RESULT_TOOL = "submit_result"
        private val FALLBACK_MODELS = listOf("claude-opus-5", "claude-sonnet-5-5", "claude-fable-5")
        private val gson = Gson()

        val organizeSchema: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf("items" to mapOf("type" to "array", "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "bvid" to mapOf("type" to "string"), "fit" to mapOf("type" to "string", "enum" to Prompts.FITS),
                    "kind" to mapOf("type" to "string", "enum" to Prompts.KINDS), "level" to mapOf("type" to "string", "enum" to Prompts.LEVELS),
                    "edition" to mapOf("type" to "string"), "summary" to mapOf("type" to "string"),
                    "quality" to mapOf("type" to "integer"), "comment" to mapOf("type" to "string"),
                ),
                "required" to listOf("bvid", "fit", "kind", "level", "edition", "summary", "quality", "comment"),
                "additionalProperties" to false,
            ))),
            "required" to listOf("items"),
            "additionalProperties" to false,
        )

        val intentSchema: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "grade" to mapOf("type" to "integer"), "subject" to mapOf("type" to "string", "enum" to Taxonomy.allSubjects + ""),
                "volume" to mapOf("type" to "string"), "topic" to mapOf("type" to "string"), "query" to mapOf("type" to "string"),
            ),
            "required" to listOf("grade", "subject", "volume", "topic", "query"),
            "additionalProperties" to false,
        )

        /** 从文字里取出 JSON 对象：容忍 ```json 代码块和前后多余的文字。 */
        fun parseJsonText(raw: String): JsonObject {
            var text = raw.trim()
            if (text.startsWith("```")) text = text.substringAfter('\n').substringBeforeLast("```")
            runCatching { return JsonParser.parseString(text).asJsonObject }
            val l = text.indexOf('{'); val r = text.lastIndexOf('}')
            if (l in 0 until r) runCatching { return JsonParser.parseString(text.substring(l, r + 1)).asJsonObject }
            throw AppException("大模型返回的不是有效 JSON")
        }

        private fun pick(v: String?, choices: List<String>, fallback: String) = v?.takeIf { it in choices } ?: fallback
        private fun JsonObject.s(k: String) = get(k)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

        /** 按 Schema 校验一条判断：缺字段或取值不合法时用保守的默认值，避免一条坏数据弄坏整页。 */
        fun judgement(row: JsonObject): Pair<String, AIJudgement>? {
            val bvid = row.s("bvid")?.trim().orEmpty().ifEmpty { return null }
            val q = row.s("quality")?.toDoubleOrNull()?.toInt() ?: 3
            return bvid to AIJudgement(
                fit = pick(row.s("fit"), Prompts.FITS, "相关"), kind = pick(row.s("kind"), Prompts.KINDS, "其他"),
                level = pick(row.s("level"), Prompts.LEVELS, "中等"), edition = row.s("edition").orEmpty().take(12),
                summary = row.s("summary").orEmpty().take(60), quality = q.coerceIn(1, 5), comment = row.s("comment").orEmpty().take(40),
            )
        }
    }

    private fun client(secret: String): AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl(config.baseUrl.trimEnd('/'))
        .apply { if (config.auth == "bearer") authToken(secret) else apiKey(secret) }
        .putHeader("anthropic-version", config.effectiveApiVersion)
        .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
        .maxRetries(2)
        .build()

    private fun json(value: Any): JsonValue = JsonValue.from(value)

    suspend fun call(model: String, system: String, user: String, schema: Map<String, Any>, maxTokens: Int): JsonObject = withContext(Dispatchers.IO) {
        val secret = AIConfig.secret ?: throw AppException("没有填写大模型密钥")
        val mode = config.effectiveJsonMode
        var systemText = system
        val builder = MessageCreateParams.builder().model(model).maxTokens(maxTokens.toLong()).addUserMessage(user)
        val output = OutputConfig.builder()
        var hasOutput = false
        if (config.effort.isNotEmpty()) { output.effort(OutputConfig.Effort.of(config.effort)); hasOutput = true }
        when (mode) {
            "tool" -> {
                @Suppress("UNCHECKED_CAST")
                val props = schema["properties"] as Map<String, Any>
                builder.addTool(Tool.builder().name(RESULT_TOOL).description("提交结果。结果只能通过这个工具提交。")
                    .inputSchema(Tool.InputSchema.builder()
                        .properties(json(props))
                        .putAdditionalProperty("required", json(schema["required"]!!))
                        .putAdditionalProperty("additionalProperties", json(false))
                        .build())
                    .build())
                // 新的 Claude 模型不接受强制调用指定工具，改为自动选择加提示；其他模型直接强制调用
                builder.toolChoice(if (model.startsWith("claude-")) ToolChoice.ofAuto(ToolChoiceAuto.builder().build())
                                   else ToolChoice.ofTool(ToolChoiceTool.builder().name(RESULT_TOOL).build()))
                systemText += "\n\n用 $RESULT_TOOL 工具提交结果，不要直接回答。"
            }
            "prompt" -> systemText += "\n\n只输出一个符合下面 JSON Schema 的 JSON 对象，不要输出其他任何文字：\n" + gson.toJson(schema)
            else -> {
                val s = JsonOutputFormat.Schema.builder()
                schema.forEach { (k, v) -> s.putAdditionalProperty(k, json(v)) }
                output.format(JsonOutputFormat.builder().schema(s.build()).build())
                hasOutput = true
            }
        }
        builder.system(systemText)
        if (hasOutput) builder.outputConfig(output.build())
        // 安全分类器误拒时，由服务端换一个模型重跑；只有官方接口支持
        if (config.isOfficial && FALLBACK_MODELS.any { model.startsWith(it) }) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", json("default"))
        }
        val message = try {
            client(secret).messages().create(builder.build())
        } catch (e: UnauthorizedException) { throw AppException("密钥无效")
        } catch (e: PermissionDeniedException) { throw AppException("这个密钥没有调用 $model 的权限")
        } catch (e: NotFoundException) { throw AppException("找不到模型 $model，检查设置里的模型名")
        } catch (e: RateLimitException) { throw AppException("大模型请求太频繁，稍后再试")
        } catch (e: BadRequestException) { throw AppException("大模型接口拒绝了请求参数：${e.message?.take(160)}")
        } catch (e: AnthropicServiceException) { throw AppException("大模型接口返回 ${e.statusCode()}")
        } catch (e: AnthropicIoException) { throw AppException("连不上大模型接口 ${config.baseUrl}") }
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw AppException("大模型拒绝了这次请求")
            StopReason.MAX_TOKENS -> throw AppException("大模型输出被截断，可在设置里调小每批条数")
            else -> {}
        }
        for (block in message.content()) {
            val tool = block.toolUse().orElse(null) ?: continue
            if (tool.name() == RESULT_TOOL) {
                val parsed = runCatching { JsonParser.parseString(tool._input().toString()).asJsonObject }.getOrNull()
                    ?: gson.toJsonTree(tool._input().convert(Map::class.java)).asJsonObject
                return@withContext parsed
            }
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isEmpty()) throw AppException("大模型没有返回结果")
        parseJsonText(text)
    }

    private fun describe(i: Int, c: Course): String {
        val f = SimpleDateFormat("yyyy-MM", Locale.CHINA)
        val parts = mutableListOf("$i. [${c.bvid}] 标题：${c.title}", "UP主：${c.author}",
            if (c.duration >= 3600) "总时长：%.1f小时".format(c.duration / 3600.0) else "时长：${c.duration / 60}分钟",
            "播放：" + (if (c.play >= 10000) "%.1f万".format(c.play / 1e4) else "${c.play}"),
            "收藏：" + (if (c.favorites >= 10000) "%.1f万".format(c.favorites / 1e4) else "${c.favorites}"),
            "发布：" + (if (c.pubdate > 0) f.format(Date(c.pubdate * 1000L)) else "未知"))
        if (c.episodes > 0) parts.add("集数：${c.episodes}")
        if (c.tagtext.isNotEmpty()) parts.add("标签：${c.tagtext.take(100)}")
        if (c.desc.isNotEmpty()) parts.add("简介：${c.desc}")
        return parts.joinToString("｜")
    }

    /** 返回 [bvid: 判断]。部分批次失败时返回已完成的部分；全部失败才抛错。 */
    suspend fun organize(gradeName: String, stage: String, subject: String, volume: String, items: List<Course>, model: String): Map<String, AIJudgement> = coroutineScope {
        val target = "$gradeName $subject $volume（$stage）"
        val limit = Semaphore(config.concurrency.coerceAtLeast(1))
        val results = items.chunked(config.batchSize.coerceAtLeast(1)).map { chunk ->
            async {
                limit.withPermit {
                    runCatching {
                        val user = "目标分类：$target\n候选视频：\n" + chunk.mapIndexed { i, c -> describe(i + 1, c) }.joinToString("\n")
                        val data = call(model, Prompts.ORGANIZE_SYSTEM, user, organizeSchema, 8000)
                        var rows = data.get("items")
                        if (rows != null && rows.isJsonPrimitive) rows = runCatching { JsonParser.parseString(rows.asString) }.getOrNull() // 有的模型会把数组写成字符串
                        val wanted = chunk.map { it.bvid }.toSet()
                        rows?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { r -> r.takeIf { it.isJsonObject }?.asJsonObject?.let(::judgement) }
                            ?.filter { it.first in wanted }?.toMap().orEmpty()
                    }
                }
            }
        }.awaitAll()
        val merged = results.mapNotNull { it.getOrNull() }.fold(emptyMap<String, AIJudgement>()) { acc, m -> acc + m }
        if (merged.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        merged
    }

    suspend fun parseIntent(text: String, currentGrade: Int?, model: String): SearchIntent {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val term = if (month >= 8 || month == 1) "上学期（秋季，9 月到次年 1 月）" else "下学期（春季，2 月到 7 月）"
        val user = "当前年级：${currentGrade?.let(Taxonomy::gradeName) ?: "未知"}\n当前学期：$term\n用户的话：$text"
        val d = call(model, Prompts.INTENT_SYSTEM, user, intentSchema, 2000)
        val g = Classify.int(d.get("grade"))
        var grade = if (g in 1..12) g else 0
        var subject = pick(d.s("subject"), Taxonomy.allSubjects, "")
        // 初中叫道德与法治，高中叫政治；不在这个年级科目表里的学科不给跳转
        if (grade > 0) {
            if (subject == "道德与法治" || subject == "政治") subject = if (grade >= 10) "政治" else "道德与法治"
            if (subject !in Taxonomy.subjects(grade)) subject = ""
        }
        var volume = d.s("volume").orEmpty().take(12)
        if (grade == 0 || subject.isEmpty() || volume !in Taxonomy.volumes(grade, subject)) volume = ""
        return SearchIntent(grade, subject, volume, d.s("topic").orEmpty().take(30), d.s("query").orEmpty().take(40))
    }
}
