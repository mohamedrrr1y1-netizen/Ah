package com.hamo.ultimate

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt

private val Ink = Color(0xFF101010)
private val Panel = Color(0xFF1A1A1A)
private val Gold = Color(0xFFD4AF37)
private val TextMain = Color(0xFFF4F0E4)
private val TextMuted = Color(0xFFB8B3A6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("hamo_ultimate", MODE_PRIVATE)
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var apiUrl by remember { mutableStateOf(prefs.getString("api_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000") }
            var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
            var cashNumber by remember { mutableStateOf("01092642484") }
            var withdrawalFeeBps by remember { mutableIntStateOf(30) }
            var binanceFeeBps by remember { mutableIntStateOf(200) }
            var currentUser by remember { mutableStateOf<JSONObject?>(null) }
            var screen by remember { mutableStateOf(if (token.isBlank()) "auth" else "home") }
            var authMode by remember { mutableStateOf("login") }
            var email by remember { mutableStateOf("") }
            var username by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var verifyCode by remember { mutableStateOf("") }
            var resetCode by remember { mutableStateOf("") }
            var devCodeMessage by remember { mutableStateOf("") }
            var result by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var refreshVersion by remember { mutableIntStateOf(0) }
            var adminTab by remember { mutableStateOf("overview") }
            var selectedProduct by remember { mutableStateOf<JSONObject?>(null) }
            var selectedAdminProduct by remember { mutableStateOf<JSONObject?>(null) }
            var editGame by remember { mutableStateOf("") }
            var editProductName by remember { mutableStateOf("") }
            var editProductPrice by remember { mutableStateOf("") }
            var editProductFee by remember { mutableStateOf(true) }
            var editProductEnabled by remember { mutableStateOf(true) }
            var playerId by remember { mutableStateOf("") }
            var depositAmount by remember { mutableStateOf("") }
            var senderPhone by remember { mutableStateOf("") }
            var depositReference by remember { mutableStateOf("") }
            var proofUri by remember { mutableStateOf<Uri?>(null) }
            var withdrawalAmount by remember { mutableStateOf("") }
            var recipient by remember { mutableStateOf("") }
            var transferReference by remember { mutableStateOf("") }
            var addGame by remember { mutableStateOf("Free Fire") }
            var addProductName by remember { mutableStateOf("") }
            var addProductPrice by remember { mutableStateOf("") }
            var addProductFee by remember { mutableStateOf(true) }
            var feeProduct by remember { mutableStateOf("0.3") }
            var feeWithdrawal by remember { mutableStateOf("0.3") }
            var feeBinance by remember { mutableStateOf("2") }
            var level2XPText by remember { mutableStateOf("500") }
            var level3XPText by remember { mutableStateOf("1500") }
            var xpPerOrderText by remember { mutableStateOf("10") }
            var minWithdrawalText by remember { mutableStateOf("10") }
            var maxWithdrawalText by remember { mutableStateOf("50000") }
            var binanceEnabled by remember { mutableStateOf(false) }
            var profitEnabled by remember { mutableStateOf(true) }
            var queryUsers by remember { mutableStateOf("") }
            var selectedUser by remember { mutableStateOf<JSONObject?>(null) }
            var proofImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            var proofImageId by remember { mutableStateOf("") }
            var adjustmentAmount by remember { mutableStateOf("") }
            var adjustmentReason by remember { mutableStateOf("") }
            var adjustmentXP by remember { mutableStateOf("10") }
            var adjustmentKind by remember { mutableStateOf("balance") }
            var binanceMode by remember { mutableStateOf("deposit") }

            val proofPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> proofUri = uri }

            LaunchedEffect(apiUrl, refreshVersion) {
                try {
                    val conf = JSONObject(Api.call(apiUrl.trimEnd('/'), "/config", "GET", null, null))
                    cashNumber = conf.optString("vodafone_cash_number", "01092642484")
                    withdrawalFeeBps = conf.optInt("withdrawal_fee_bps", 30)
                    binanceFeeBps = conf.optInt("binance_fee_bps", 200)
                } catch (_: Exception) { /* The screen still shows the configured fallback while the API is unreachable. */ }
            }

            suspend fun api(path: String, method: String = "GET", body: JSONObject? = null): String {
                return Api.call(apiUrl.trimEnd('/'), path, method, body, token.ifBlank { null })
            }
            fun runApi(path: String, method: String = "GET", body: JSONObject? = null, success: String = "تمت العملية") {
                scope.launch {
                    loading = true
                    message = ""
                    try {
                        result = api(path, method, body)
                        message = success
                        refreshVersion++
                    } catch (e: Exception) {
                        message = e.message ?: "حدث خطأ غير معروف"
                    } finally { loading = false }
                }
            }

            LaunchedEffect(token) {
                if (token.isNotBlank()) {
                    try {
                        currentUser = JSONObject(Api.call(apiUrl.trimEnd('/'), "/me", "GET", null, token))
                        if (screen == "auth") screen = "home"
                    } catch (e: Exception) {
                        if (e.message?.contains("401") == true || e.message?.contains("Authentication") == true) {
                            token = ""; currentUser = null; screen = "auth"
                            prefs.edit().remove("token").apply()
                        } else message = e.message ?: "تعذر الاتصال بالخادم"
                    }
                } else currentUser = null
            }

            LaunchedEffect(token, screen, refreshVersion, adminTab) {
                if (token.isBlank()) return@LaunchedEffect
                val path = when (screen) {
                    "wallet" -> "/wallet"
                    "store" -> "/products"
                    "requests" -> "/requests"
                    "notifications" -> "/notifications"
                    "xp" -> "/xp"
                    "profile" -> "/me"
                    "profit" -> "/wallet"
                    "admin" -> when (adminTab) {
                        "overview" -> "/admin/overview"
                        "deposits" -> "/admin/deposits?status_filter=review"
                        "withdrawals" -> "/admin/withdrawals?status_filter=active"
                        "orders" -> "/admin/orders?status_filter=active"
                        "products" -> "/admin/products"
                        "settings" -> "/admin/settings"
                        "users" -> "/admin/users?q=${java.net.URLEncoder.encode(queryUsers, "UTF-8")}"
                        else -> "/admin/overview"
                    }
                    else -> ""
                }
                if (path.isNotBlank()) {
                    try {
                        if (screen == "wallet") {
                            val w = JSONObject(Api.call(apiUrl.trimEnd('/'), "/wallet", "GET", null, token))
                            val t = JSONArray(Api.call(apiUrl.trimEnd('/'), "/transactions", "GET", null, token))
                            result = JSONObject().put("wallet", w).put("transactions", t).toString(2)
                        } else {
                            result = Api.call(apiUrl.trimEnd('/'), path, "GET", null, token)
                        }
                        if (screen == "admin" && adminTab == "settings") {
                            val s = JSONObject(result)
                            feeProduct = (s.optInt("product_fee_bps", 30) / 100.0).toString()
                            feeWithdrawal = (s.optInt("withdrawal_fee_bps", 30) / 100.0).toString()
                            feeBinance = (s.optInt("binance_fee_bps", 200) / 100.0).toString()
                            level2XPText = s.optInt("level2_xp", 500).toString()
                            level3XPText = s.optInt("level3_xp", 1500).toString()
                            xpPerOrderText = s.optInt("xp_completed_game_order", 10).toString()
                            minWithdrawalText = (s.optInt("minimum_withdrawal_piasters", 1000) / 100.0).toString()
                            maxWithdrawalText = (s.optInt("maximum_withdrawal_piasters", 5_000_000) / 100.0).toString()
                            binanceEnabled = s.optBoolean("binance_enabled", false)
                            profitEnabled = s.optBoolean("profit_enabled", true)
                        }
                    } catch (e: Exception) {
                        result = ""
                        message = e.message ?: "تعذر تحميل البيانات"
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Gold, onPrimary = Ink, background = Ink, onBackground = TextMain,
                surface = Panel, onSurface = TextMain, secondary = TextMuted,
                outline = Color(0xFF62552E), error = Color(0xFFE57373)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Header(title = "HAMO ULTIMATE", subtitle = "👑 ʜᴀᴍᴏ • متجر الألعاب والمحفظة")
                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f))) {
                                Text(message, modifier = Modifier.padding(12.dp), color = TextMain, fontSize = 13.sp)
                            }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                        Spacer(Modifier.height(8.dp))

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (screen) {
                            "auth" -> AuthPage(
                                apiUrl = apiUrl, onApiUrl = { apiUrl = it; prefs.edit().putString("api_url", it).apply() },
                                mode = authMode, onMode = { authMode = it }, email = email, onEmail = { email = it },
                                username = username, onUsername = { username = it }, password = password, onPassword = { password = it },
                                code = verifyCode, onCode = { verifyCode = it }, resetCode = resetCode, onResetCode = { resetCode = it },
                                devCodeMessage = devCodeMessage,
                                onSubmit = {
                                    scope.launch {
                                        loading = true; message = ""
                                        try {
                                            val url = apiUrl.trimEnd('/')
                                            when (authMode) {
                                                "register" -> {
                                                    val r = JSONObject(Api.call(url, "/auth/register", "POST", JSONObject().put("email", email).put("username", username).put("password", password), null))
                                                    devCodeMessage = if (r.has("development_code")) "رمز التطوير: ${r.optString("development_code")}" else "افتح بريدك الإلكتروني للحصول على رمز التأكيد."
                                                    authMode = "verify"; message = r.optString("message", "تم إنشاء الحساب")
                                                }
                                                "verify" -> {
                                                    val r = JSONObject(Api.call(url, "/auth/verify", "POST", JSONObject().put("email", email).put("code", verifyCode), null))
                                                    token = r.optString("access_token"); currentUser = r.optJSONObject("user")
                                                    prefs.edit().putString("token", token).apply(); screen = "home"; message = "تم تأكيد الحساب وتسجيل الدخول"
                                                }
                                                "forgot" -> {
                                                    val r = JSONObject(Api.call(url, "/auth/forgot-password", "POST", JSONObject().put("email", email), null))
                                                    devCodeMessage = if (r.has("development_code")) "رمز الاستعادة للتطوير: ${r.optString("development_code")}" else r.optString("message")
                                                    authMode = "reset"; message = r.optString("message")
                                                }
                                                "reset" -> {
                                                    val r = JSONObject(Api.call(url, "/auth/reset-password", "POST", JSONObject().put("email", email).put("code", resetCode).put("new_password", password), null))
                                                    authMode = "login"; message = r.optString("message", "تم تغيير كلمة المرور")
                                                }
                                                else -> {
                                                    val r = JSONObject(Api.call(url, "/auth/login", "POST", JSONObject().put("email", email).put("password", password), null))
                                                    token = r.optString("access_token"); currentUser = r.optJSONObject("user")
                                                    prefs.edit().putString("token", token).apply(); screen = "home"; message = "أهلًا بك في HAMO ULTIMATE"
                                                }
                                            }
                                        } catch (e: Exception) { message = e.message ?: "تعذر إكمال العملية" }
                                        finally { loading = false }
                                    }
                                }
                            )
                            "home" -> HomePage(
                                user = currentUser, onNavigate = { screen = it; message = ""; refreshVersion++ },
                                onAdmin = { screen = "admin"; adminTab = "overview"; message = ""; refreshVersion++ }
                            )
                            "wallet" -> DataPage("المحفظة الرقمية والسجل المالي", result, listOf("الرصيد المتاح والمحجوز وسجل المعاملات يتم تحميلها من الخادم.", "تُعرض القيم بالجنيه المصري."))
                            "notifications" -> DataPage("الإشعارات", result, listOf("الإشعارات حسب حسابك من الخادم."))
                            "xp" -> DataPage("سجل XP والمستويات", result, listOf("تُحتسب النقاط من أحداث مؤهلة وغير مكررة."))
                            "store" -> StorePage(
                                rawProducts = result, onBuy = { selectedProduct = it; playerId = "" },
                                onRefresh = { refreshVersion++ }
                            )
                            "deposit" -> DepositPage(
                                cashNumber = cashNumber, amount = depositAmount, onAmount = { depositAmount = it }, sender = senderPhone, onSender = { senderPhone = it },
                                reference = depositReference, onReference = { depositReference = it }, proofUri = proofUri,
                                onPickProof = { proofPicker.launch("image/*") }, onSubmit = {
                                    val amount = piasters(depositAmount)
                                    if (amount <= 0 || senderPhone.length < 5 || depositReference.length < 4 || proofUri == null) {
                                        message = "أدخل مبلغًا صحيحًا ورقم المرسل والمرجع واختر صورة الإثبات."; return@DepositPage
                                    }
                                    scope.launch {
                                        loading = true
                                        try {
                                            result = Api.uploadDeposit(apiUrl.trimEnd('/'), token, context, proofUri!!, "vodafone_cash", amount, senderPhone, depositReference)
                                            message = "تم إرسال طلب الإيداع للمراجعة؛ لم يُضف الرصيد بعد."
                                            depositAmount = ""; depositReference = ""; proofUri = null; refreshVersion++
                                        } catch (e: Exception) { message = e.message ?: "فشل إرسال الطلب" }
                                        finally { loading = false }
                                    }
                                }
                            )
                            "withdraw" -> WithdrawalPage(
                                feeBps = withdrawalFeeBps, amount = withdrawalAmount, onAmount = { withdrawalAmount = it }, recipient = recipient, onRecipient = { recipient = it },
                                channel = "vodafone_cash", onSubmit = {
                                    val amount = piasters(withdrawalAmount)
                                    if (amount <= 0 || recipient.length < 5) { message = "أدخل مبلغًا صحيحًا ورقم فودافون كاش المستفيد."; return@WithdrawalPage }
                                    runApi("/withdrawals", "POST", JSONObject().put("amount_piasters", amount).put("channel", "vodafone_cash").put("recipient", recipient), "أُرسل طلب السحب للمراجعة؛ لم يتم التحويل بعد.")
                                }
                            )
                            "binance" -> BinancePage(
                                feeBps = binanceFeeBps, mode = binanceMode, onMode = { binanceMode = it }, amount = depositAmount, onAmount = { depositAmount = it },
                                sender = senderPhone, onSender = { senderPhone = it }, reference = depositReference, onReference = { depositReference = it },
                                recipient = recipient, onRecipient = { recipient = it }, proofUri = proofUri,
                                onPickProof = { proofPicker.launch("image/*") },
                                onDeposit = {
                                    if (piasters(depositAmount) <= 0 || senderPhone.length < 5 || depositReference.length < 4 || proofUri == null) {
                                        message = "أدخل بيانات العملية واختر صورة الإثبات."; return@BinancePage
                                    }
                                    scope.launch {
                                        loading = true
                                        try {
                                            result = Api.uploadDeposit(apiUrl.trimEnd('/'), token, context, proofUri!!, "binance", piasters(depositAmount), senderPhone, depositReference)
                                            message = "تم إرسال طلب Binance للمراجعة؛ لن يضاف الرصيد قبل التحقق الفعلي."
                                            refreshVersion++
                                        } catch (e: Exception) { message = e.message ?: "فشل إرسال الطلب" }
                                        finally { loading = false }
                                    }
                                },
                                onWithdraw = {
                                    val amount = piasters(depositAmount)
                                    if (amount <= 0 || recipient.length < 5) { message = "أدخل مبلغًا صحيحًا ومعرّف المستفيد."; return@BinancePage }
                                    runApi("/withdrawals", "POST", JSONObject().put("amount_piasters", amount).put("channel", "binance").put("recipient", recipient), "أُرسل طلب Binance للمراجعة؛ لم يتم التحويل بعد.")
                                }
                            )
                            "requests" -> DataPage("طلباتي", result, listOf("تتضمن طلبات الشحن والإيداع والسحب وحالاتها من الخادم."))
                            "profit" -> ProfitPage(result)
                            "profile" -> ProfilePage(currentUser, onLogout = {
                                token = ""; currentUser = null; screen = "auth"; authMode = "login"; result = ""; message = "تم تسجيل الخروج"
                                prefs.edit().remove("token").apply()
                            }, onReload = { refreshVersion++ })
                            "admin" -> AdminPage(
                                tab = adminTab, onTab = { adminTab = it; result = ""; refreshVersion++ }, raw = result,
                                transferReference = transferReference, onTransferReference = { transferReference = it },
                                addGame = addGame, onAddGame = { addGame = it }, addName = addProductName, onAddName = { addProductName = it },
                                addPrice = addProductPrice, onAddPrice = { addProductPrice = it }, addFee = addProductFee, onAddFee = { addProductFee = it },
                                feeProduct = feeProduct, onFeeProduct = { feeProduct = it }, feeWithdrawal = feeWithdrawal, onFeeWithdrawal = { feeWithdrawal = it },
                                feeBinance = feeBinance, onFeeBinance = { feeBinance = it },
                                level2XPText = level2XPText, onLevel2XPText = { level2XPText = it },
                                level3XPText = level3XPText, onLevel3XPText = { level3XPText = it },
                                xpPerOrderText = xpPerOrderText, onXpPerOrderText = { xpPerOrderText = it },
                                minWithdrawalText = minWithdrawalText, onMinWithdrawalText = { minWithdrawalText = it },
                                maxWithdrawalText = maxWithdrawalText, onMaxWithdrawalText = { maxWithdrawalText = it },
                                binanceEnabled = binanceEnabled, onBinanceEnabled = { binanceEnabled = it },
                                profitEnabled = profitEnabled, onProfitEnabled = { profitEnabled = it }, queryUsers = queryUsers, onQueryUsers = { queryUsers = it },
                                onReload = { refreshVersion++ }, onAction = { path, method, body, done -> runApi(path, method, body, done) },
                                onSelectUser = { selectedUser = it; adjustmentAmount = ""; adjustmentReason = ""; adjustmentXP = "10" },
                                onEditProduct = { p ->
                                    selectedAdminProduct = p
                                    editGame = p.optString("game")
                                    editProductName = p.optString("name")
                                    editProductPrice = ((p.optJSONObject("price")?.optInt("piasters") ?: 0) / 100.0).toString()
                                    editProductFee = p.optBoolean("fee_enabled", true)
                                    editProductEnabled = p.optBoolean("enabled", true)
                                },
                                onViewProof = { id ->
                                    scope.launch {
                                        loading = true
                                        try {
                                            val bytes = Api.downloadProof(apiUrl.trimEnd('/'), token, id)
                                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            if (bitmap == null) message = "تعذر عرض صورة الإثبات." else { proofImage = bitmap.asImageBitmap(); proofImageId = id }
                                        } catch (e: Exception) { message = e.message ?: "تعذر تحميل صورة الإثبات" }
                                        finally { loading = false }
                                    }
                                }
                            )
                            else -> HomePage(user = currentUser, onNavigate = { screen = it }, onAdmin = { screen = "admin" })
                        }
                        }

                        Spacer(Modifier.height(8.dp))
                        if (screen != "auth") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { screen = "home"; message = "" }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Gold.copy(alpha = .7f))) { Text("الرئيسية") }
                                OutlinedButton(onClick = { refreshVersion++ }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Gold.copy(alpha = .7f))) { Text("تحديث") }
                            }
                        }
                    }
                }
            }

            selectedAdminProduct?.let { product ->
                AlertDialog(
                    onDismissRequest = { selectedAdminProduct = null },
                    title = { Text("تعديل المنتج ${product.optInt("id")}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = editGame, onValueChange = { editGame = it }, label = { Text("اللعبة") }, singleLine = true)
                            OutlinedTextField(value = editProductName, onValueChange = { editProductName = it }, label = { Text("اسم الباقة") }, singleLine = true)
                            OutlinedTextField(value = editProductPrice, onValueChange = { editProductPrice = it }, label = { Text("السعر بالجنيه") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = editProductFee, onCheckedChange = { editProductFee = it }); Text("تطبيق العمولة") }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = editProductEnabled, onCheckedChange = { editProductEnabled = it }); Text("المنتج مفعّل") }
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                        val price = piasters(editProductPrice)
                        if (editGame.length < 2 || editProductName.length < 2 || price <= 0) { message = "تحقق من اسم اللعبة والباقة والسعر."; return@TextButton }
                        val body = JSONObject().put("game", editGame).put("name", editProductName).put("price_piasters", price).put("fee_enabled", editProductFee).put("enabled", editProductEnabled)
                        val id = product.optInt("id")
                        selectedAdminProduct = null
                        runApi("/admin/products/$id", "PATCH", body, "تم تعديل المنتج وحفظ التغييرات على الخادم.")
                    }) { Text("حفظ التعديل") } },
                    dismissButton = { TextButton(onClick = { selectedAdminProduct = null }) { Text("إلغاء") } },
                    containerColor = Panel, titleContentColor = Gold, textContentColor = TextMain
                )
            }

            proofImage?.let { bitmap ->
                AlertDialog(
                    onDismissRequest = { proofImage = null; proofImageId = "" },
                    title = { Text("إثبات الإيداع • ${proofImageId.take(12)}") },
                    text = { Image(bitmap = bitmap, contentDescription = "Deposit proof", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit) },
                    confirmButton = { TextButton(onClick = { proofImage = null; proofImageId = "" }) { Text("إغلاق") } },
                    containerColor = Panel, titleContentColor = Gold, textContentColor = TextMain
                )
            }

            selectedProduct?.let { product ->
                AlertDialog(
                    onDismissRequest = { selectedProduct = null },
                    title = { Text("تأكيد شراء ${product.optString("name")}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("اللعبة: ${product.optString("game")}")
                            Text("الإجمالي: ${product.optJSONObject("total")?.optString("formatted") ?: "راجع السعر"}")
                            OutlinedTextField(value = playerId, onValueChange = { playerId = it }, label = { Text("Player ID") }, singleLine = true)
                            Text("تأكد من Player ID قبل التأكيد. سيبدأ الطلب بحالة قيد المراجعة.", color = TextMuted, fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val pid = playerId.trim()
                            if (pid.length < 2) { message = "أدخل Player ID صحيحًا."; return@TextButton }
                            val body = JSONObject().put("product_id", product.optInt("id")).put("player_id", pid)
                                .put("idempotency_key", UUID.randomUUID().toString())
                            selectedProduct = null
                            scope.launch {
                                loading = true
                                try { result = api("/orders", "POST", body); message = "تم إنشاء الطلب. التنفيذ غير مؤكد حتى يحدّث المطور الحالة."; screen = "requests"; refreshVersion++ }
                                catch (e: Exception) { message = e.message ?: "فشل إنشاء الطلب" }
                                finally { loading = false }
                            }
                        }) { Text("شراء الآن") }
                    },
                    dismissButton = { TextButton(onClick = { selectedProduct = null }) { Text("إلغاء") } },
                    containerColor = Panel, titleContentColor = Gold, textContentColor = TextMain
                )
            }

            selectedUser?.let { u ->
                AlertDialog(
                    onDismissRequest = { selectedUser = null },
                    title = { Text("إدارة ${u.optString("username")}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ID: ${u.optInt("id")} • ${u.optString("email")}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { adjustmentKind = "balance" }) { Text("الرصيد") }
                                OutlinedButton(onClick = { adjustmentKind = "xp" }) { Text("XP") }
                            }
                            OutlinedTextField(value = adjustmentAmount, onValueChange = { adjustmentAmount = it }, label = { Text(if (adjustmentKind == "balance") "مبلغ EGP (+/-)" else "نقاط XP (+/-)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = adjustmentReason, onValueChange = { adjustmentReason = it }, label = { Text("السبب الإلزامي") })
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                        val amount = adjustmentAmount.toDoubleOrNull()
                        if (amount == null || adjustmentReason.length < 3) { message = "أدخل مبلغًا أو نقاطًا وسببًا صحيحًا."; return@TextButton }
                        val body = if (adjustmentKind == "balance") JSONObject().put("amount_piasters", (amount * 100).roundToInt()).put("reason", adjustmentReason)
                            else JSONObject().put("points", amount.toInt()).put("reason", adjustmentReason)
                        val path = if (adjustmentKind == "balance") "/admin/users/${u.optInt("id")}/balance" else "/admin/users/${u.optInt("id")}/xp"
                        selectedUser = null
                        runApi(path, "POST", body, "تم تسجيل التعديل وإضافته إلى سجل التدقيق.")
                    }) { Text("تأكيد التعديل") } },
                    dismissButton = { TextButton(onClick = { selectedUser = null }) { Text("إغلاق") } },
                    containerColor = Panel, titleContentColor = Gold, textContentColor = TextMain
                )
            }
        }
    }
}

private object Api {
    suspend fun call(baseUrl: String, path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
                throw IOException("HTTP $code: $detail")
            }
            text
        } finally { connection.disconnect() }
    }

    suspend fun downloadProof(baseUrl: String, token: String, depositId: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(baseUrl.trimEnd('/') + "/admin/deposits/$depositId/proof").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val detail = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
                throw IOException("HTTP $code: $detail")
            }
            conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    suspend fun uploadDeposit(baseUrl: String, token: String, context: Context, uri: Uri,
                              channel: String, amountPiasters: Int, sender: String, reference: String): String = withContext(Dispatchers.IO) {
        val boundary = "----HAMO${UUID.randomUUID().toString().replace("-", "")}" 
        val conn = (URL(baseUrl.trimEnd('/') + "/deposits").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                }
                field("channel", channel)
                field("amount_piasters", amountPiasters.toString())
                field("sender_phone", sender)
                field("reference", reference)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"proof\"; filename=\"proof.$ext\"\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192); var total = 0; var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        total += count
                        if (total > 5 * 1024 * 1024) throw IOException("صورة الإثبات أكبر من 5 ميجابايت.")
                        out.write(buffer, 0, count)
                    }
                } ?: throw IOException("تعذر قراءة صورة الإثبات.")
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
                throw IOException("HTTP $code: $detail")
            }
            text
        } finally { conn.disconnect() }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text(title, color = Gold, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Text(subtitle, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AuthPage(apiUrl: String, onApiUrl: (String) -> Unit, mode: String, onMode: (String) -> Unit,
                     email: String, onEmail: (String) -> Unit, username: String, onUsername: (String) -> Unit,
                     password: String, onPassword: (String) -> Unit, code: String, onCode: (String) -> Unit,
                     resetCode: String, onResetCode: (String) -> Unit, devCodeMessage: String, onSubmit: () -> Unit) {
    val title = when (mode) { "register" -> "إنشاء حساب"; "verify" -> "تأكيد البريد الإلكتروني"; "forgot" -> "استعادة كلمة المرور"; "reset" -> "تعيين كلمة مرور جديدة"; else -> "تسجيل الدخول" }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("اتصل بخادم HAMO الآمن. على هاتف حقيقي استخدم عنوان HTTPS للخادم؛ عنوان 10.0.2.2 مخصص لمحاكي Android.", color = TextMuted, fontSize = 12.sp)
        OutlinedTextField(value = apiUrl, onValueChange = onApiUrl, label = { Text("عنوان API") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = email, onValueChange = onEmail, label = { Text("البريد الإلكتروني") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (mode == "register") OutlinedTextField(value = username, onValueChange = onUsername, label = { Text("اسم المستخدم") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (mode == "verify") OutlinedTextField(value = code, onValueChange = onCode, label = { Text("رمز التأكيد من 6 أرقام") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (mode == "reset") OutlinedTextField(value = resetCode, onValueChange = onResetCode, label = { Text("رمز الاستعادة") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (mode in listOf("login", "register", "reset")) OutlinedTextField(value = password, onValueChange = onPassword, label = { Text(if (mode == "reset") "كلمة المرور الجديدة (10 أحرف على الأقل)" else "كلمة المرور") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
        if (devCodeMessage.isNotBlank()) Text(devCodeMessage, color = Gold, fontSize = 13.sp)
        OutlinedButton(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text(when (mode) { "register" -> "إنشاء الحساب"; "verify" -> "تأكيد وتسجيل الدخول"; "forgot" -> "إرسال رمز الاستعادة"; "reset" -> "حفظ كلمة المرور"; else -> "دخول آمن" }, color = Gold) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mode != "login") TextButton(onClick = { onMode("login") }) { Text("تسجيل الدخول") }
            if (mode == "login") TextButton(onClick = { onMode("register") }) { Text("حساب جديد") }
            if (mode in listOf("login", "forgot")) TextButton(onClick = { onMode(if (mode == "forgot") "login" else "forgot") }) { Text("نسيت كلمة المرور؟") }
        }
    }
}

@Composable
private fun HomePage(user: JSONObject?, onNavigate: (String) -> Unit, onAdmin: () -> Unit) {
    val isAdmin = user?.optString("role") == "admin"
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("أهلًا ${user?.optString("username", "HAMO") ?: "بك"} 👑", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("المستوى ${user?.optInt("level", 1)} • XP ${user?.optInt("xp", 0)}", color = Gold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            val tiles = listOf(
                "wallet" to "المحفظة الرقمية", "store" to "متجر شحن الألعاب", "deposit" to "إيداع فودافون كاش",
                "withdraw" to "سحب من المحفظة", "binance" to "Binance | إيداع وسحب", "requests" to "طلباتي والمعاملات",
                "notifications" to "الإشعارات", "xp" to "سجل XP والمستويات", "profit" to "نظام الربح المتكامل 🔒", "profile" to "الملف الشخصي"
            )
            items(tiles) { (screen, label) ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .25f))) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = TextMain, fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = { onNavigate(screen) }, border = BorderStroke(1.dp, Gold.copy(alpha = .7f))) { Text("فتح", color = Gold) }
                    }
                }
            }
            if (isAdmin) item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("لوحة المطور المحمية", color = Gold, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onAdmin, border = BorderStroke(1.dp, Gold)) { Text("دخول", color = Gold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataPage(title: String, raw: String, notes: List<String> = emptyList()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        notes.forEach { Text(it, color = TextMuted, fontSize = 12.sp) }
        Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp)) {
                Text(prettyJson(raw), color = TextMain, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StorePage(rawProducts: String, onBuy: (JSONObject) -> Unit, onRefresh: () -> Unit) {
    val products = remember(rawProducts) { try { JSONArray(rawProducts) } catch (_: Exception) { JSONArray() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("متجر شحن الألعاب", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("الأسعار والعمولة والإجمالي محسوبة من الخادم. المنتجات غير المضافة أو المعطلة لن تظهر.", color = TextMuted, fontSize = 12.sp)
        Row { OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, Gold)) { Text("تحديث المنتجات", color = Gold) } }
        if (products.length() == 0) Text("لا توجد باقات مفعلة بعد. يجب على المطور إضافة باقات بأسعار فعلية من لوحة المطور.", color = TextMuted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(products.length()) { index ->
                val p = products.optJSONObject(index) ?: JSONObject()
                Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .25f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${p.optString("game")} • ${p.optString("name")}", color = Gold, fontWeight = FontWeight.Bold)
                        Text("السعر الأساسي: ${p.optJSONObject("price")?.optString("formatted")} | العمولة: ${p.optJSONObject("fee")?.optString("formatted")}", color = TextMuted, fontSize = 12.sp)
                        Text("الإجمالي: ${p.optJSONObject("total")?.optString("formatted")}", color = TextMain)
                        OutlinedButton(onClick = { onBuy(p) }, border = BorderStroke(1.dp, Gold)) { Text("اختيار الباقة", color = Gold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepositPage(cashNumber: String, amount: String, onAmount: (String) -> Unit, sender: String, onSender: (String) -> Unit,
                        reference: String, onReference: (String) -> Unit, proofUri: Uri?, onPickProof: () -> Unit, onSubmit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("إيداع عبر فودافون كاش", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .4f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("رقم التحويل", color = TextMuted, fontSize = 12.sp)
                Text(cashNumber, color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { clipboard.setText(AnnotatedString(cashNumber)) }) { Text("نسخ الرقم", color = Gold) }
                Text("حوّل أولًا، ثم أرسل بيانات التحويل. لا تضف الرصيد اعتمادًا على الصورة وحدها.", color = TextMuted, fontSize = 12.sp)
            }
        }
        OutlinedTextField(value = amount, onValueChange = onAmount, label = { Text("المبلغ المحوّل بالجنيه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(value = sender, onValueChange = onSender, label = { Text("رقم الهاتف المُرسِل") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        OutlinedTextField(value = reference, onValueChange = onReference, label = { Text("رقم العملية المرجعي") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = onPickProof, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text(if (proofUri == null) "اختيار صورة إثبات التحويل" else "تم اختيار صورة الإثبات ✓", color = Gold) }
        OutlinedButton(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text("إرسال طلب الإيداع", color = Gold) }
        Text("الحالة بعد الإرسال: قيد المراجعة. الرصيد لا يتغير إلا بعد التحقق والموافقة من المطور.", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun WithdrawalPage(feeBps: Int, amount: String, onAmount: (String) -> Unit, recipient: String, onRecipient: (String) -> Unit,
                           channel: String, onSubmit: () -> Unit) {
    val gross = piasters(amount)
    val fee = feePiasters(gross, feeBps)
    val net = (gross - fee).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("سحب من المحفظة", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("عمولة السحب حسب الإعداد الحالي: ${feeBps / 100.0}%. يُخصم المبلغ الإجمالي من الرصيد المتاح ويُحجز أثناء المراجعة.", color = TextMuted, fontSize = 12.sp)
        OutlinedTextField(value = amount, onValueChange = onAmount, label = { Text("المبلغ بالجنيه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Text("تفصيل قبل التأكيد: المبلغ ${formatPiasters(gross)} ج • العمولة ${formatPiasters(fee)} ج • الصافي المتوقع ${formatPiasters(net)} ج", color = Gold, fontSize = 13.sp)
        OutlinedTextField(value = recipient, onValueChange = onRecipient, label = { Text("رقم فودافون كاش المستفيد") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        OutlinedButton(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text("تأكيد طلب السحب", color = Gold) }
        Text("لا يُعد السحب مكتملًا إلا بعد تسجيل مرجع التحويل الفعلي.", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun BinancePage(feeBps: Int, mode: String, onMode: (String) -> Unit, amount: String, onAmount: (String) -> Unit,
                        sender: String, onSender: (String) -> Unit, reference: String, onReference: (String) -> Unit,
                        recipient: String, onRecipient: (String) -> Unit, proofUri: Uri?, onPickProof: () -> Unit,
                        onDeposit: () -> Unit, onWithdraw: () -> Unit) {
    val gross = piasters(amount)
    val fee = feePiasters(gross, feeBps)
    val net = (gross - fee).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Binance | إيداع وسحب", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("هذا ليس محفظة Binance الرسمية. لا يوجد تنفيذ تلقائي مفترض؛ تفعيل الطلبات مشروط بمزوّد معتمد ومختبَر.", color = TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMode("deposit") }, border = BorderStroke(1.dp, Gold)) { Text("طلب إيداع", color = Gold) }
            OutlinedButton(onClick = { onMode("withdraw") }, border = BorderStroke(1.dp, Gold)) { Text("طلب سحب", color = Gold) }
        }
        OutlinedTextField(value = amount, onValueChange = onAmount, label = { Text("المبلغ بالجنيه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        if (mode == "withdraw") Text("تفصيل قبل التأكيد: المبلغ ${formatPiasters(gross)} ج • عمولة الخدمة ${formatPiasters(fee)} ج • الصافي المتوقع ${formatPiasters(net)} ج", color = Gold, fontSize = 13.sp)
        if (mode == "deposit") {
            OutlinedTextField(value = sender, onValueChange = onSender, label = { Text("معرّف/رقم المرسل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = reference, onValueChange = onReference, label = { Text("مرجع العملية") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = onPickProof, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text(if (proofUri == null) "اختيار إثبات العملية" else "تم اختيار الإثبات ✓", color = Gold) }
            OutlinedButton(onClick = onDeposit, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text("إرسال طلب Binance", color = Gold) }
        } else {
            OutlinedTextField(value = recipient, onValueChange = onRecipient, label = { Text("معرّف مستفيد Binance أو بيانات الاستلام") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("العمولة الافتراضية 2%، وأي رسوم خارجية يجب أن تظهر منفصلة قبل تفعيل المزوّد.", color = TextMuted, fontSize = 12.sp)
            OutlinedButton(onClick = onWithdraw, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Gold)) { Text("إرسال طلب السحب", color = Gold) }
        }
    }
}

@Composable
private fun ProfitPage(raw: String) {
    val wallet = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    val unlocked = wallet.optBoolean("profit_unlocked", false)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("نظام الربح المتكامل 👑", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .4f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (unlocked) "القسم مفتوح ✓" else "نظام الربح المتكامل مقفول 🔒", color = if (unlocked) Gold else TextMain, fontWeight = FontWeight.Bold)
                Text(if (unlocked) "المزايا المتاحة تخضع لإعدادات المطور وشروطها. لا توجد أرباح مضمونة." else "واصل استخدام التطبيق وإكمال معاملاتك المؤهلة للوصول إلى Level 3 وفتح المزايا.", color = TextMuted)
                Text("مستواك: ${wallet.optInt("level", 1)} • XP: ${wallet.optInt("xp", 0)}", color = TextMain)
                Text("Level 2: ${wallet.optInt("level2_xp", 500)} XP • Level 3: ${wallet.optInt("level3_xp", 1500)} XP", color = Gold)
            }
        }
    }
}

@Composable
private fun ProfilePage(user: JSONObject?, onLogout: () -> Unit, onReload: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الملف الشخصي", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(prettyJson(user?.toString() ?: "{}"), color = TextMain, fontSize = 13.sp)
        OutlinedButton(onClick = onReload, border = BorderStroke(1.dp, Gold)) { Text("تحديث الملف", color = Gold) }
        OutlinedButton(onClick = onLogout, border = BorderStroke(1.dp, Gold)) { Text("تسجيل الخروج", color = Gold) }
    }
}

@Composable
private fun AdminPage(
    tab: String, onTab: (String) -> Unit, raw: String,
    transferReference: String, onTransferReference: (String) -> Unit,
    addGame: String, onAddGame: (String) -> Unit, addName: String, onAddName: (String) -> Unit,
    addPrice: String, onAddPrice: (String) -> Unit, addFee: Boolean, onAddFee: (Boolean) -> Unit,
    feeProduct: String, onFeeProduct: (String) -> Unit, feeWithdrawal: String, onFeeWithdrawal: (String) -> Unit,
    feeBinance: String, onFeeBinance: (String) -> Unit,
    level2XPText: String, onLevel2XPText: (String) -> Unit, level3XPText: String, onLevel3XPText: (String) -> Unit,
    xpPerOrderText: String, onXpPerOrderText: (String) -> Unit,
    minWithdrawalText: String, onMinWithdrawalText: (String) -> Unit,
    maxWithdrawalText: String, onMaxWithdrawalText: (String) -> Unit,
    binanceEnabled: Boolean, onBinanceEnabled: (Boolean) -> Unit,
    profitEnabled: Boolean, onProfitEnabled: (Boolean) -> Unit, queryUsers: String, onQueryUsers: (String) -> Unit,
    onReload: () -> Unit, onAction: (String, String, JSONObject?, String) -> Unit, onSelectUser: (JSONObject) -> Unit,
    onEditProduct: (JSONObject) -> Unit, onViewProof: (String) -> Unit
) {
    val tabs = listOf("overview" to "نظرة عامة", "deposits" to "الإيداعات", "withdrawals" to "السحب", "orders" to "طلبات الألعاب", "products" to "المنتجات", "settings" to "الإعدادات", "users" to "المستخدمون")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("لوحة المطور 👑", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("الوصول محمي من الخادم حسب دور الحساب، وليس بإخفاء الواجهة فقط.", color = TextMuted, fontSize = 11.sp)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tabs) { (key, label) ->
                OutlinedButton(onClick = { onTab(key) }, border = BorderStroke(1.dp, if (key == tab) Gold else Gold.copy(alpha = .3f))) { Text(label, color = if (key == tab) Gold else TextMuted, fontSize = 11.sp) }
            }
        }
        when (tab) {
            "products" -> {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("إضافة باقة شحن", color = Gold, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = addGame, onValueChange = onAddGame, label = { Text("اسم اللعبة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = addName, onValueChange = onAddName, label = { Text("اسم الباقة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = addPrice, onValueChange = onAddPrice, label = { Text("السعر بالجنيه") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = addFee, onCheckedChange = onAddFee) ; Text("تطبيق العمولة", color = TextMain) }
                        OutlinedButton(onClick = {
                            val p = piasters(addPrice)
                            if (addGame.length < 2 || addName.length < 2 || p <= 0) return@OutlinedButton
                            onAction("/admin/products", "POST", JSONObject().put("game", addGame).put("name", addName).put("price_piasters", p).put("fee_enabled", addFee).put("enabled", true), "تم إنشاء المنتج. أضف سعرًا حقيقيًا ومتفقًا عليه.")
                            onAddName(""); onAddPrice("")
                        }, border = BorderStroke(1.dp, Gold)) { Text("حفظ المنتج", color = Gold) }
                    }
                }
            }
            "settings" -> {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("العمولات تُدخل كنسبة مئوية", color = Gold, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = feeProduct, onValueChange = onFeeProduct, label = { Text("عمولة المنتجات %") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = feeWithdrawal, onValueChange = onFeeWithdrawal, label = { Text("عمولة السحب %") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = feeBinance, onValueChange = onFeeBinance, label = { Text("عمولة Binance %") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = level2XPText, onValueChange = onLevel2XPText, label = { Text("حد XP للمستوى الثاني") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = level3XPText, onValueChange = onLevel3XPText, label = { Text("حد XP للمستوى الثالث") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = xpPerOrderText, onValueChange = onXpPerOrderText, label = { Text("XP لكل طلب لعبة مكتمل") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = minWithdrawalText, onValueChange = onMinWithdrawalText, label = { Text("أقل سحب بالجنيه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = maxWithdrawalText, onValueChange = onMaxWithdrawalText, label = { Text("أعلى سحب بالجنيه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = binanceEnabled, onCheckedChange = onBinanceEnabled); Spacer(Modifier.width(8.dp)); Text("تفعيل طلبات Binance يدويًا", color = TextMain) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = profitEnabled, onCheckedChange = onProfitEnabled); Spacer(Modifier.width(8.dp)); Text("تفعيل قسم الربح", color = TextMain) }
                        OutlinedButton(onClick = {
                            val a = feeProduct.toDoubleOrNull(); val b = feeWithdrawal.toDoubleOrNull(); val c = feeBinance.toDoubleOrNull()
                            val l2 = level2XPText.toIntOrNull(); val l3 = level3XPText.toIntOrNull(); val xpOrder = xpPerOrderText.toIntOrNull()
                            val minW = minWithdrawalText.toDoubleOrNull(); val maxW = maxWithdrawalText.toDoubleOrNull()
                            if (a == null || b == null || c == null || listOf(a,b,c).any { it < 0 || it > 50 } || l2 == null || l3 == null || xpOrder == null || minW == null || maxW == null || l2 >= l3 || l2 < 1 || xpOrder < 0 || minW <= 0 || maxW < minW) return@OutlinedButton
                            val values = JSONObject().put("product_fee_bps", (a*100).roundToInt()).put("withdrawal_fee_bps", (b*100).roundToInt()).put("binance_fee_bps", (c*100).roundToInt())
                                .put("level2_xp", l2).put("level3_xp", l3).put("xp_completed_game_order", xpOrder)
                                .put("minimum_withdrawal_piasters", piasters(minW.toString())).put("maximum_withdrawal_piasters", piasters(maxW.toString()))
                                .put("binance_enabled", binanceEnabled).put("profit_enabled", profitEnabled)
                            onAction("/admin/settings", "PUT", JSONObject().put("values", values), "تم حفظ الإعدادات على الخادم.")
                        }, border = BorderStroke(1.dp, Gold)) { Text("حفظ الإعدادات", color = Gold) }
                        Text("ملاحظة: لا تُفعّل Binance إلا بعد تجهيز مزوّد تحويل متوافق واختباره خارج التطبيق.", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
            "users" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = queryUsers, onValueChange = onQueryUsers, label = { Text("بحث بالبريد أو الاسم") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(onClick = onReload, border = BorderStroke(1.dp, Gold)) { Text("بحث", color = Gold) }
                }
                val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(arr.length()) { i ->
                        val u = arr.optJSONObject(i) ?: JSONObject()
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${u.optString("username")} • ID ${u.optInt("id")}", color = Gold, fontWeight = FontWeight.Bold)
                                Text(u.optString("email"), color = TextMuted, fontSize = 11.sp)
                                Text("رصيد: ${u.optJSONObject("balance_available")?.optString("formatted")} | Level ${u.optInt("level")} • ${u.optInt("xp")} XP", color = TextMain, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onSelectUser(u) }, border = BorderStroke(1.dp, Gold)) { Text("تعديل رصيد/XP", color = Gold, fontSize = 11.sp) }
                                    OutlinedButton(onClick = { onAction("/admin/users/${u.optInt("id")}/block", "POST", JSONObject().put("blocked", !u.optBoolean("is_blocked")), if (u.optBoolean("is_blocked")) "تم إلغاء الحظر" else "تم حظر الحساب") }, border = BorderStroke(1.dp, Gold)) { Text(if (u.optBoolean("is_blocked")) "إلغاء الحظر" else "حظر", color = Gold, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
            "deposits", "withdrawals", "orders" -> {
                if (tab == "withdrawals") {
                    OutlinedTextField(value = transferReference, onValueChange = onTransferReference, label = { Text("مرجع التحويل الفعلي (مطلوب للإكمال)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(arr.length()) { i ->
                        val o = arr.optJSONObject(i) ?: JSONObject()
                        val id = o.optString("id")
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), border = BorderStroke(1.dp, Gold.copy(alpha = .2f))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${tab.uppercase()} • ${id.take(12)}", color = Gold, fontWeight = FontWeight.Bold)
                                Text(prettyJson(o.toString()), color = TextMuted, fontSize = 10.sp, maxLines = 9)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (tab) {
                                        "deposits" -> {
                                            OutlinedButton(onClick = { onViewProof(id) }, border = BorderStroke(1.dp, Gold)) { Text("عرض الإثبات", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/deposits/$id/review", "POST", JSONObject().put("decision", "accept"), "تم قبول الإيداع وإضافة الرصيد بعد تسجيل القرار." ) }, border = BorderStroke(1.dp, Gold)) { Text("قبول", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/deposits/$id/review", "POST", JSONObject().put("decision", "reject").put("reason", "تعذر التحقق من الإيداع"), "تم رفض طلب الإيداع." ) }, border = BorderStroke(1.dp, Gold)) { Text("رفض", color = Gold) }
                                        }
                                        "withdrawals" -> {
                                            OutlinedButton(onClick = {
                                                if (transferReference.trim().length < 4) return@OutlinedButton
                                                onAction("/admin/withdrawals/$id/review", "POST", JSONObject().put("decision", "accept").put("transfer_reference", transferReference.trim()), "تم تسجيل السحب كمكتمل بعد إدخال مرجع التحويل." )
                                            }, border = BorderStroke(1.dp, Gold)) { Text("تأكيد التحويل", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/withdrawals/$id/review", "POST", JSONObject().put("decision", "reject").put("reason", "تم رفض طلب السحب"), "تم رفض السحب وإعادة المبلغ المحجوز." ) }, border = BorderStroke(1.dp, Gold)) { Text("رفض وإرجاع الرصيد", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/withdrawals/$id/review", "POST", JSONObject().put("decision", "processing"), "تم وضع الطلب قيد التنفيذ." ) }, border = BorderStroke(1.dp, Gold)) { Text("قيد التنفيذ", color = Gold) }
                                        }
                                        "orders" -> {
                                            OutlinedButton(onClick = { onAction("/admin/orders/$id/review", "POST", JSONObject().put("decision", "processing"), "تم تحديث الطلب إلى قيد التنفيذ." ) }, border = BorderStroke(1.dp, Gold)) { Text("قيد التنفيذ", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/orders/$id/review", "POST", JSONObject().put("decision", "complete"), "تم تسجيل اكتمال الطلب يدويًا؛ تأكد من تنفيذ الشحن فعليًا أولًا." ) }, border = BorderStroke(1.dp, Gold)) { Text("تم التنفيذ فعليًا", color = Gold) }
                                            OutlinedButton(onClick = { onAction("/admin/orders/$id/review", "POST", JSONObject().put("decision", "reject").put("reason", "تعذر تنفيذ الطلب"), "تم رفض الطلب وإعادة المبلغ." ) }, border = BorderStroke(1.dp, Gold)) { Text("رفض/استرداد", color = Gold) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (arr.length() == 0) item { Text("لا توجد طلبات في هذا العرض.", color = TextMuted) }
                }
            }
            else -> Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = Panel)) { Text(prettyJson(raw), Modifier.padding(12.dp), color = TextMain, fontSize = 12.sp) }
        }
        if (tab in listOf("overview", "products", "settings")) {
            if (tab == "overview") Text("${prettyJson(raw)}", color = TextMain, fontSize = 12.sp)
            if (tab == "products") {
                val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
                Text("المنتجات الحالية: ${arr.length()}", color = TextMuted, fontSize = 11.sp)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(arr.length()) { i ->
                        val p = arr.optJSONObject(i) ?: JSONObject()
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${p.optString("game")} • ${p.optString("name")}", color = Gold)
                                    Text("${p.optJSONObject("price")?.optString("formatted")} • ${if (p.optBoolean("enabled")) "مفعّل" else "متوقف"}", color = TextMuted, fontSize = 11.sp)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { onEditProduct(p) }, border = BorderStroke(1.dp, Gold)) { Text("تعديل", color = Gold) }
                                    OutlinedButton(onClick = { onAction("/admin/products/${p.optInt("id")}", "PATCH", JSONObject().put("enabled", !p.optBoolean("enabled")), "تم تغيير حالة المنتج") }, border = BorderStroke(1.dp, Gold)) { Text(if (p.optBoolean("enabled")) "إيقاف" else "تفعيل", color = Gold) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun piasters(raw: String): Int = ((raw.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) * 100).roundToInt().coerceAtLeast(0)
private fun feePiasters(amountPiasters: Int, basisPoints: Int): Int = ((amountPiasters.toLong() * basisPoints.coerceAtLeast(0) + 5000L) / 10000L).toInt()
private fun formatPiasters(value: Int): String = String.format(java.util.Locale.US, "%.2f", value / 100.0)

private fun prettyJson(raw: String): String {
    if (raw.isBlank()) return "لا توجد بيانات محمّلة. تحقق من اتصال الخادم ثم اضغط تحديث."
    return try {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
    } catch (_: Exception) { raw }
}
