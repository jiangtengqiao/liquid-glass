package com.liquidglass.app.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 货币汇率实时获取与缓存。
 *
 * - 数据源：open.er-api.com（免费、无需 key），基准 USD
 * - 换算成 CNY 基准（1 单位外币 = ? CNY），与 [currencyUnits] 的 toBase 语义一致
 * - 缓存 24h：进入货币 Tab 优先用缓存展示，过期则后台刷新
 * - 网络/解析失败：保留当前（缓存或默认）汇率，状态标记为 ERROR，UI 据此提示
 *
 * 暴露 [current]（Compose 可观察）与 [status]，供 UnitConverterScreen 渲染。
 */
object CurrencyRateStore {

    private const val PREFS = "currency_rates"
    private const val KEY_RATES = "rates_json"
    private const val KEY_TS = "rates_ts"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24h

    // 货币符号 → 中文名（顺序即列表顺序，CNY 必须首位作为基准）
    private val SYMBOL_TO_NAME = linkedMapOf(
        "CNY" to "人民币", "USD" to "美元", "EUR" to "欧元", "GBP" to "英镑",
        "JPY" to "日元", "KRW" to "韩元", "HKD" to "港币", "AUD" to "澳元", "CAD" to "加元"
    )

    // 默认汇率（CNY 基准）：网络与缓存均不可用时兜底
    private val DEFAULT_RATES = linkedMapOf(
        "CNY" to 1.0, "USD" to 7.25, "EUR" to 7.85, "GBP" to 9.20,
        "JPY" to 0.048, "KRW" to 0.0054, "HKD" to 0.93, "AUD" to 4.75, "CAD" to 5.30
    )

    enum class Status { LOADING, OK, STALE, ERROR }

    /** 当前汇率单位列表（Compose 可观察） */
    val current = mutableStateOf(buildUnits(DEFAULT_RATES))
    /** 数据新鲜度状态，UI 据此展示提示 */
    val status = mutableStateOf(Status.LOADING)

    private fun buildUnits(rates: Map<String, Double>): List<UnitDef> =
        SYMBOL_TO_NAME.map { (sym, name) ->
            UnitDef(name, sym, rates[sym] ?: DEFAULT_RATES[sym] ?: 1.0)
        }

    /** 读取缓存（若有）。在进入货币 Tab 时调用，保证首屏不空白 */
    fun loadCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_RATES, null) ?: return
        val ts = prefs.getLong(KEY_TS, 0L)
        try {
            current.value = buildUnits(parseRates(JSONObject(cached)))
            status.value = if (System.currentTimeMillis() - ts < MAX_AGE_MS) Status.OK else Status.STALE
        } catch (_: Exception) { /* 缓存损坏，保留默认 */ }
    }

    /** 拉取最新汇率（IO 线程）。成功更新 state+缓存；失败保留当前并标记 ERROR */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        status.value = Status.LOADING
        try {
            // open.er-api.com 免费、无需 key，返回 rates 以 USD 为基准
            val url = URL("https://open.er-api.com/v6/latest/USD")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LiquidGlassApp/1.0")
            }
            // 校验 HTTP 状态码，避免把错误页/HTML 当数据解析
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                status.value = Status.ERROR
                return@withContext
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(raw)
            val ratesUsd = json.getJSONObject("rates")
            // CNYperX = cnyPerUsd / usdPerX（usdPerX = ratesUsd[X]，即 1 USD = ? X）
            // 用 optDouble 替代 getDouble，键缺失时回退默认值，避免 JSONException 导致整体崩溃
            val cnyPerUsd = ratesUsd.optDouble("CNY", DEFAULT_RATES["CNY"] ?: 1.0)
            val rates = linkedMapOf<String, Double>()
            SYMBOL_TO_NAME.keys.forEach { sym ->
                val defaultRate = DEFAULT_RATES[sym] ?: 1.0
                if (sym == "CNY") {
                    rates[sym] = 1.0
                } else if (sym == "USD") {
                    rates[sym] = cnyPerUsd
                } else {
                    val usdPerX = ratesUsd.optDouble(sym, Double.NaN)
                    rates[sym] = if (usdPerX.isNaN() || usdPerX == 0.0) defaultRate else cnyPerUsd / usdPerX
                }
            }
            current.value = buildUnits(rates)
            status.value = Status.OK
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RATES, JSONObject().apply { rates.forEach { (k, v) -> put(k, v) } }.toString())
                .putLong(KEY_TS, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
            // 失败：保留当前汇率（缓存或默认），标记 ERROR 供 UI 提示
            status.value = Status.ERROR
        }
    }

    private fun parseRates(json: JSONObject): Map<String, Double> {
        val rates = linkedMapOf<String, Double>()
        SYMBOL_TO_NAME.keys.forEach { sym ->
            rates[sym] = json.optDouble(sym, DEFAULT_RATES[sym] ?: 1.0)
        }
        return rates
    }
}
