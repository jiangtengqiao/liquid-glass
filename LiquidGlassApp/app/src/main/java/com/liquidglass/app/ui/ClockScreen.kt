package com.liquidglass.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.liquidglass.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ── 天气数据 ──
data class WeatherData(
    val temperature: Double = 0.0,
    val weatherCode: Int = 0,
    val cityName: String = "定位中...",
    val humidity: Int = 0,
    val windSpeed: Double = 0.0,
    val pressure: Double = 0.0,
    val visibility: Double = 0.0,
    val feelsLike: Double = 0.0,
    val hourlyTemp: List<Double> = emptyList(),
    val hourlyTime: List<String> = emptyList(),
    val hourlyCode: List<Int> = emptyList(),
    val dailyMax: List<Double> = emptyList(),
    val dailyMin: List<Double> = emptyList(),
    val dailyCode: List<Int> = emptyList(),
    val dailyDate: List<String> = emptyList()
)

// ── 城市搜索结果 ──
data class CityResult(
    val name: String,
    val country: String,
    val admin1: String,
    val lat: Double,
    val lon: Double
)

// ── 世界时钟时区 ──
data class WorldClock(
    val id: String,
    val label: String,
    val timezone: String,
    val flag: String
)

val defaultWorldClocks = listOf(
    WorldClock("beijing",   "北京",     "Asia/Shanghai",        "\uD83C\uDDE8\uD83C\uDDF3"),
    WorldClock("tokyo",     "东京",     "Asia/Tokyo",          "\uD83C\uDDEF\uD83C\uDDF5"),
    WorldClock("newyork",   "纽约",     "America/New_York",     "\uD83C\uDDFA\uD83C\uDDF8"),
    WorldClock("london",    "伦敦",     "Europe/London",        "\uD83C\uDDEC\uD83C\uDDE7"),
    WorldClock("paris",     "巴黎",     "Europe/Paris",         "\uD83C\uDDEB\uD83C\uDDF7"),
    WorldClock("sydney",    "悉尼",     "Australia/Sydney",     "\uD83C\uDDE6\uD83C\uDDFA"),
    WorldClock("dubai",     "迪拜",     "Asia/Dubai",          "\uD83C\uDDE6\uD83C\uDDEA"),
    WorldClock("losangeles","洛杉矶",   "America/Los_Angeles",  "\uD83C\uDDFA\uD83C\uDDF8"),
    WorldClock("moscow",    "莫斯科",   "Europe/Moscow",        "\uD83C\uDDF7\uD83C\uDDFA"),
    WorldClock("kolkata",   "孟买",     "Asia/Kolkata",        "\uD83C\uDDEE\uD83C\uDDF3"),
)

enum class ClockTab { LOCAL, SEARCH, WORLD }

@Composable
fun ClockScreen(animTime: Float, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var weather by remember { mutableStateOf(WeatherData()) }
    var locationGranted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(ClockTab.LOCAL) }
    var myClocks by remember { mutableStateOf(defaultWorldClocks) }

    // 城市搜索
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CityResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchedWeather by remember { mutableStateOf<WeatherData?>(null) }
    var searchedCityName by remember { mutableStateOf("") }
    var isSearchWeatherLoading by remember { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA) }
    val weekFormat = remember { SimpleDateFormat("第w周", Locale.CHINA) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationGranted = granted
        if (granted) {
            scope.launch {
                getLocationAndWeather(context) { w -> weather = w }
            }
        }
    }

    fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    // 时钟更新
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 天气加载：IP定位先行，GPS后台更新
    LaunchedEffect(Unit) {
        isLoading = true
        // 第一步：立即用IP定位获取天气（秒出结果）
        try {
            withContext(Dispatchers.IO) {
                val ipUrl = URL("http://ip-api.com/json/?lang=zh-CN&fields=city,lat,lon")
                val ipResult = ipUrl.readText()
                val ipJson = JSONObject(ipResult)
                val lat = ipJson.optDouble("lat", 39.9)
                val lon = ipJson.optDouble("lon", 116.4)
                val city = ipJson.optString("city", "北京")
                fetchWeather(lat, lon, city) { w ->
                    weather = w
                    isLoading = false
                }
            }
        } catch (_: Exception) {
            fetchWeather(39.9, 116.4, "北京") { w ->
                weather = w
                isLoading = false
            }
        }

        // 第二步：GPS后台静默更新（更精确的位置）
        if (checkPermission()) {
            locationGranted = true
            getLocationAndWeather(context) { w ->
                weather = w
            }
        } else {
            locationGranted = false
        }
    }

    val cal = Calendar.getInstance()
    val scrollState = rememberScrollState()

    LiquidGlassScaffold(animTime = animTime) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = appTextSecondary())
                }
                Text("时钟·天气", fontSize = 16.sp, color = appTextSecondary(), modifier = Modifier.weight(1f))
                if (!locationGranted) {
                    TextButton(onClick = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }) {
                        Icon(Icons.Default.MyLocation, null, tint = FluidCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("开启定位", fontSize = 12.sp, color = FluidCyan)
                    }
                }
                if (isLoading && selectedTab == ClockTab.LOCAL) {
                    Spacer(modifier = Modifier.width(4.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = FluidCyan, strokeWidth = 1.5.dp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 时钟卡片
            Box(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 32.dp, glassAlpha = 0.15f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp)) {
                    Text(
                        timeFormat.format(Date(currentTime)),
                        fontSize = 48.sp, fontWeight = FontWeight.Thin, color = appTextPrimary(),
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(dateFormat.format(Date(currentTime)), fontSize = 15.sp, fontWeight = FontWeight.Light, color = appTextSecondary(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(weekFormat.format(Date(currentTime)), fontSize = 12.sp, color = appTextTertiary(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab 切换
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClockTabButton("本地天气", ClockTab.LOCAL, selectedTab) { selectedTab = it }
                ClockTabButton("搜索城市", ClockTab.SEARCH, selectedTab) { selectedTab = it }
                ClockTabButton("世界时钟", ClockTab.WORLD, selectedTab) { selectedTab = it }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 内容区
            when (selectedTab) {
                ClockTab.LOCAL -> LocalWeatherContent(weather, locationGranted, animTime)
                ClockTab.SEARCH -> CitySearchContent(
                    context, searchQuery, searchResults, isSearching,
                    searchedWeather, searchedCityName, isSearchWeatherLoading,
                    onQueryChange = { searchQuery = it },
                    onSearch = {
                        scope.launch {
                            if (searchQuery.isBlank()) return@launch
                            isSearching = true
                            searchResults = emptyList()
                            searchResults = searchCity(searchQuery)
                            isSearching = false
                        }
                    },
                    onSelectCity = { city ->
                        scope.launch {
                            searchQuery = city.name
                            searchResults = emptyList()
                            isSearchWeatherLoading = true
                            searchedCityName = city.name
                            searchedWeather = null
                            fetchWeather(city.lat, city.lon, city.name) { w ->
                                searchedWeather = w
                                isSearchWeatherLoading = false
                            }
                        }
                    }
                )
                ClockTab.WORLD -> WorldClockContent(currentTime, myClocks, animTime)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun ClockTabButton(label: String, tab: ClockTab, selected: ClockTab, onClick: (ClockTab) -> Unit) {
    val isSelected = selected == tab
    val bgModifier = if (isSelected) {
        Modifier.background(Brush.horizontalGradient(listOf(FluidCyan.copy(alpha = 0.2f), FluidPurple.copy(alpha = 0.2f))))
    } else {
        Modifier.background(Color.White.copy(alpha = 0.05f))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(bgModifier)
            .clickable { onClick(tab) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (isSelected) FluidCyan else TextTertiary,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ═══════════════ 本地天气 ═══════════════
@Composable
fun LocalWeatherContent(weather: WeatherData, locationGranted: Boolean, animTime: Float) {
    Column(modifier = Modifier.animateContentSize()) {
        // 天气主卡片
        Box(
            modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = FluidCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(weather.cityName, fontSize = 14.sp, color = appTextSecondary())
                        if (!locationGranted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("(IP定位)", fontSize = 10.sp, color = appTextTertiary())
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${weather.temperature.toInt()}°C", fontSize = 52.sp, fontWeight = FontWeight.Thin, color = appTextPrimary())
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("体感 ${weather.feelsLike.toInt()}°C · ${weatherCodeToText(weather.weatherCode)}", fontSize = 13.sp, color = appTextSecondary())
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        WeatherDetailChip("湿度", "${weather.humidity}%")
                        WeatherDetailChip("风速", "${weather.windSpeed} km/h")
                        WeatherDetailChip("气压", "${weather.pressure.toInt()} hPa")
                    }
                }
                Text(weatherCodeToEmoji(weather.weatherCode), fontSize = 64.sp)
            }
        }

        // 逐小时预报
        if (weather.hourlyTemp.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f).padding(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = FluidCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("逐小时预报", fontSize = 12.sp, color = appTextTertiary())
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val startIndex = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                        val endIndex = (startIndex + 9).coerceAtMost(weather.hourlyTemp.size)
                        for (i in startIndex until endIndex) {
                            if (i < weather.hourlyTemp.size) {
                                HourlyForecastItem(
                                    time = if (i < weather.hourlyTime.size) weather.hourlyTime[i] else "$i:00",
                                    temp = "${weather.hourlyTemp[i].toInt()}°",
                                    code = if (i < weather.hourlyCode.size) weather.hourlyCode[i] else 0
                                )
                            }
                        }
                    }
                }
            }
        }

        // 7日预报
        if (weather.dailyMax.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f).padding(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, null, tint = FluidPurple, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("7日预报", fontSize = 12.sp, color = appTextTertiary())
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    for (i in weather.dailyDate.indices) {
                        if (i >= weather.dailyMax.size || i >= weather.dailyMin.size) break
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(weather.dailyDate[i], fontSize = 12.sp, color = appTextSecondary(), modifier = Modifier.width(50.dp))
                            Text(weatherCodeToEmoji(if (i < weather.dailyCode.size) weather.dailyCode[i] else 0), fontSize = 16.sp, modifier = Modifier.width(36.dp))
                            Text("${weather.dailyMin[i].toInt()}°", fontSize = 12.sp, color = appTextTertiary(), modifier = Modifier.width(36.dp))
                            LinearProgressIndicator(
                                progress = {
                                    val range = (weather.dailyMax[i] - weather.dailyMin[i]).toFloat()
                                    if (range > 0f) (range / 40f).coerceIn(0.05f, 1f) else 0.3f
                                },
                                modifier = Modifier.weight(1f).height(4.dp).padding(horizontal = 8.dp),
                                color = FluidCyan.copy(alpha = 0.6f),
                                trackColor = GlassLight
                            )
                            Text("${weather.dailyMax[i].toInt()}°", fontSize = 12.sp, color = appTextPrimary(), modifier = Modifier.width(36.dp))
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════ 城市搜索 ═══════════════
@Composable
fun CitySearchContent(
    context: Context,
    query: String,
    results: List<CityResult>,
    isSearching: Boolean,
    searchedWeather: WeatherData?,
    searchedCityName: String,
    isWeatherLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectCity: (CityResult) -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.animateContentSize()) {
        // 搜索框
        Box(
            modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = appTextTertiary(), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索城市名称...", fontSize = 14.sp, color = appTextTertiary()) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = FluidCyan
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    })
                )
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = FluidCyan, strokeWidth = 1.5.dp)
                } else {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        onSearch()
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Send, "搜索", tint = FluidCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 搜索结果
        if (results.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 16.dp, glassAlpha = 0.12f)
            ) {
                Column {
                    for ((index, city) in results.withIndex()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCity(city) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationCity, null, tint = FluidPurple, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(city.name, fontSize = 14.sp, color = appTextPrimary())
                                Text("${city.admin1}, ${city.country}", fontSize = 11.sp, color = appTextTertiary())
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = appTextTertiary(), modifier = Modifier.size(16.dp))
                        }
                        if (index < results.size - 1) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }

        // 搜索到的天气
        if (isWeatherLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 20.dp, glassAlpha = 0.12f).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = FluidCyan, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("正在获取 ${searchedCityName} 天气...", fontSize = 13.sp, color = appTextSecondary())
                }
            }
        } else if (searchedWeather != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth().glassSurface(cornerRadius = 24.dp, glassAlpha = 0.15f).padding(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationCity, null, tint = FluidPurple, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(searchedWeather!!.cityName, fontSize = 14.sp, color = appTextSecondary())
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("${searchedWeather!!.temperature.toInt()}°C", fontSize = 48.sp, fontWeight = FontWeight.Thin, color = appTextPrimary())
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("体感 ${searchedWeather!!.feelsLike.toInt()}°C · ${weatherCodeToText(searchedWeather!!.weatherCode)}", fontSize = 13.sp, color = appTextSecondary())
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            WeatherDetailChip("湿度", "${searchedWeather!!.humidity}%")
                            WeatherDetailChip("风速", "${searchedWeather!!.windSpeed} km/h")
                            WeatherDetailChip("气压", "${searchedWeather!!.pressure.toInt()} hPa")
                        }
                    }
                    Text(weatherCodeToEmoji(searchedWeather!!.weatherCode), fontSize = 60.sp)
                }
            }
        }
    }
}

// ═══════════════ 世界时钟 ═══════════════
@Composable
fun WorldClockContent(currentTime: Long, clocks: List<WorldClock>, animTime: Float) {
    Column(modifier = Modifier.animateContentSize()) {
        for (clock in clocks) {
            val tz = remember(clock.timezone) { TimeZone.getTimeZone(clock.timezone) }
            val cal = remember(currentTime, tz) {
                Calendar.getInstance(tz).apply { timeInMillis = currentTime }
            }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            val second = cal.get(Calendar.SECOND)
            val timeStr = String.format("%02d:%02d:%02d", hour, minute, second)
            val dateStr = String.format("%d月%d日", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val isDay = hour in 6..18
            val dayLabel = if (isDay) "白天" else "夜间"
            val dayColor = if (isDay) FluidOrange else FluidPurple

            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).glassSurface(cornerRadius = 18.dp, glassAlpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(clock.flag, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(clock.label, fontSize = 14.sp, color = appTextPrimary(), fontWeight = FontWeight.Medium)
                        Text(dateStr, fontSize = 11.sp, color = appTextTertiary())
                    }
                    Text(dayLabel, fontSize = 10.sp, color = dayColor, modifier = Modifier.padding(end = 8.dp))
                    Text(timeStr, fontSize = 22.sp, fontWeight = FontWeight.Thin, color = appTextPrimary(), letterSpacing = 2.sp)
                }
            }
        }
    }
}

// ── 小组件 ──
@Composable
fun WeatherDetailChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 13.sp, color = appTextPrimary(), fontWeight = FontWeight.Light)
        Text(label, fontSize = 10.sp, color = appTextTertiary())
    }
}

@Composable
fun HourlyForecastItem(time: String, temp: String, code: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time, fontSize = 10.sp, color = appTextTertiary())
        Spacer(modifier = Modifier.height(4.dp))
        Text(weatherCodeToEmoji(code), fontSize = 18.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(temp, fontSize = 12.sp, color = appTextPrimary(), fontWeight = FontWeight.Light)
    }
}

// ── 天气图标/文字 ──
private fun weatherCodeToEmoji(code: Int): String = when (code) {
    0 -> "\u2600\uFE0F"
    1, 2, 3 -> "\u26C5"
    45, 48 -> "\uD83C\uDF2B\uFE0F"
    51, 53, 55 -> "\uD83C\uDF26\uFE0F"
    61, 63, 65 -> "\uD83C\uDF27\uFE0F"
    71, 73, 75 -> "\uD83C\uDF28\uFE0F"
    80, 81, 82 -> "\uD83C\uDF27\uFE0F"
    95, 96, 99 -> "\u26C8\uFE0F"
    else -> "\u2600\uFE0F"
}

private fun weatherCodeToText(code: Int): String = when (code) {
    0 -> "晴朗"
    1 -> "大部晴朗"
    2 -> "多云"
    3 -> "阴天"
    45, 48 -> "雾"
    51 -> "小毛毛雨"
    53 -> "毛毛雨"
    55 -> "大毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    80 -> "阵雨"
    81 -> "中阵雨"
    82 -> "大阵雨"
    95 -> "雷暴"
    96, 99 -> "雷暴+冰雹"
    else -> "未知"
}

// ── 城市搜索 API ──
private suspend fun searchCity(query: String): List<CityResult> = withContext(Dispatchers.IO) {
    try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encodedQuery&count=5&language=zh&format=json")
        val result = url.readText()
        val json = JSONObject(result)
        val results = json.optJSONArray("results") ?: JSONArray()
        val cities = mutableListOf<CityResult>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            cities.add(CityResult(
                name = item.optString("name", ""),
                country = item.optString("country", ""),
                admin1 = item.optString("admin1", ""),
                lat = item.optDouble("latitude", 0.0),
                lon = item.optDouble("longitude", 0.0)
            ))
        }
        cities
    } catch (_: Exception) {
        emptyList()
    }
}

// ── 定位与天气 ──
private fun getLocationAndWeather(context: Context, callback: (WeatherData) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var callbackCalled = false

    val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (callbackCalled) return
            callbackCalled = true
            fetchWeather(location.latitude, location.longitude, "当前位置") { data ->
                callback(data.copy(cityName = getCityName(location.latitude, location.longitude)))
            }
            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper())
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper())
        }
        val lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (lastKnown != null && !callbackCalled) {
            callbackCalled = true
            fetchWeather(lastKnown.latitude, lastKnown.longitude, "当前") { data ->
                callback(data.copy(cityName = getCityName(lastKnown.latitude, lastKnown.longitude)))
            }
        }
    } catch (_: Exception) {}

    // 超时fallback
    Thread {
        Thread.sleep(6000)
        if (!callbackCalled) {
            callbackCalled = true
            try {
                val ipUrl = URL("http://ip-api.com/json/?lang=zh-CN&fields=city,lat,lon")
                val ipResult = ipUrl.readText()
                val ipJson = JSONObject(ipResult)
                val lat = ipJson.optDouble("lat", 39.9)
                val lon = ipJson.optDouble("lon", 116.4)
                val city = ipJson.optString("city", "北京")
                fetchWeather(lat, lon, city, callback)
            } catch (_: Exception) {
                fetchWeather(39.9, 116.4, "北京", callback)
            }
        }
    }.start()
}

private fun getCityName(lat: Double, lon: Double): String {
    return try {
        val url = URL("http://ip-api.com/json/${lat},${lon}?lang=zh-CN&fields=city")
        val result = url.readText()
        val json = JSONObject(result)
        json.optString("city", "未知")
    } catch (_: Exception) { "未知" }
}

private fun fetchWeather(lat: Double, lon: Double, city: String, callback: (WeatherData) -> Unit) {
    Thread {
        try {
            val url = URL("https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current_weather=true" +
                    "&hourly=temperature_2m,weathercode,relativehumidity_2m,windspeed_10m,pressure_msl" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                    "&timezone=auto&forecast_days=7")
            val result = url.readText()
            val json = JSONObject(result)

            val current = json.optJSONObject("current_weather")
            val temp = current?.optDouble("temperature", 0.0) ?: 0.0
            val code = current?.optInt("weathercode", 0) ?: 0
            val wind = current?.optDouble("windspeed", 0.0) ?: 0.0

            val hourly = json.optJSONObject("hourly")
            val tempArr = hourly?.optJSONArray("temperature_2m")
            val codeArr = hourly?.optJSONArray("weathercode")
            val humidityArr = hourly?.optJSONArray("relativehumidity_2m")
            val timeArr = hourly?.optJSONArray("time")
            val pressureArr = hourly?.optJSONArray("pressure_msl")

            val temps = mutableListOf<Double>()
            val codes = mutableListOf<Int>()
            val times = mutableListOf<String>()
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            if (tempArr != null) for (i in 0 until tempArr.length()) temps.add(tempArr.getDouble(i))
            if (codeArr != null) for (i in 0 until codeArr.length()) codes.add(codeArr.getInt(i))
            if (timeArr != null) for (i in 0 until timeArr.length()) {
                val t = timeArr.getString(i)
                times.add(t.substring(t.length - 5))
            }

            val humidity = if (humidityArr != null && currentHour < humidityArr.length())
                humidityArr.getInt(currentHour) else 0
            val pressure = if (pressureArr != null && currentHour < pressureArr.length())
                pressureArr.getDouble(currentHour) else 0.0

            val daily = json.optJSONObject("daily")
            val dailyMaxArr = daily?.optJSONArray("temperature_2m_max")
            val dailyMinArr = daily?.optJSONArray("temperature_2m_min")
            val dailyCodeArr = daily?.optJSONArray("weathercode")
            val dailyDateArr = daily?.optJSONArray("time")

            val dailyMax = mutableListOf<Double>()
            val dailyMin = mutableListOf<Double>()
            val dailyCode = mutableListOf<Int>()
            val dailyDate = mutableListOf<String>()

            if (dailyMaxArr != null) for (i in 0 until dailyMaxArr.length()) dailyMax.add(dailyMaxArr.getDouble(i))
            if (dailyMinArr != null) for (i in 0 until dailyMinArr.length()) dailyMin.add(dailyMinArr.getDouble(i))
            if (dailyCodeArr != null) for (i in 0 until dailyCodeArr.length()) dailyCode.add(dailyCodeArr.getInt(i))
            if (dailyDateArr != null) for (i in 0 until dailyDateArr.length()) {
                val d = dailyDateArr.getString(i)
                dailyDate.add(if (i == 0) "今天" else d.substring(5))
            }

            val feelsLike = if (wind > 4.8) {
                13.12 + 0.6215 * temp - 11.37 * Math.pow(wind, 0.16) + 0.3965 * temp * Math.pow(wind, 0.16)
            } else temp

            callback(WeatherData(
                temperature = temp, weatherCode = code, cityName = city,
                humidity = humidity, windSpeed = wind, pressure = pressure,
                feelsLike = feelsLike, hourlyTemp = temps, hourlyTime = times,
                hourlyCode = codes, dailyMax = dailyMax, dailyMin = dailyMin,
                dailyCode = dailyCode, dailyDate = dailyDate
            ))
        } catch (_: Exception) {
            callback(WeatherData(temperature = 26.0, weatherCode = 0, cityName = city,
                humidity = 45, windSpeed = 5.0, pressure = 1013.0, feelsLike = 26.0))
        }
    }.start()
}