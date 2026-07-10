package com.voiceaccounting.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.voiceaccounting.app.data.AppDatabase
import com.voiceaccounting.app.data.model.Counterparty
import com.voiceaccounting.app.data.model.Transaction
import com.voiceaccounting.app.domain.VoiceProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val voiceProcessor = VoiceProcessor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "v_acc.db")
            .fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            var voiceTextResult by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            // ابزار رسمی اندروید برای فعال‌سازی میکروفون و تبدیل صوت به متن فارسی
            val speechLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                    voiceTextResult = spokenText
                }
            }
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.List, contentDescription = "دفتر روزنامه") },
                                label = { Text("روزنامه") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "اشخاص") },
                                label = { Text("اشخاص") }
                            )
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                try {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "معامله را بگویید...")
                                    }
                                    speechLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this, "خطا در راه‌اندازی میکروفون", Toast.LENGTH_SHORT).show()
                                }
                            },
                            containerColor = Color(0xFF00E676),
                            shape = CircleShape
                        ) {
                            Text("🎙️", fontSize = 24.sp)
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212))) {
                        if (selectedTab == 0) {
                            JournalScreen(db, voiceProcessor, voiceTextResult, onTextProcessed = { voiceTextResult = "" })
                        } else {
                            CounterpartiesScreen(db)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(db: AppDatabase, processor: VoiceProcessor, externalVoiceText: String, onTextProcessed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var counterparties by remember { mutableStateOf(listOf<Counterparty>()) }
    var textInput by remember { mutableStateOf("") }

    // فرم دستی تراکنش
    var manualName by remember { mutableStateOf("") }
    var manualAmount by remember { mutableStateOf("") }
    var manualRate by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("SELL") } // BUY, SELL, RECEIVE_TOMAN, PAY_TOMAN
    var manualCurrency by remember { mutableStateOf("دلار") }

    if (externalVoiceText.isNotBlank()) {
        textInput = externalVoiceText
        onTextProcessed()
    }

    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }
    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { counterparties = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("🗄️ وضعیت دیتابیس: ${transactions.size} تراکنش ذخیره شده", color = Color(0xFF00E676), fontSize = 12.sp)
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("🎙️ پردازش دستور صوتی فارسی", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            placeholder = { Text("متن ویس یا تایپ: فروختم ۱۰۰۰ دلار در ۱۵۵ به جعفر تحویل نشد") },
            trailingIcon = {
                IconButton(onClick = {
                    if (textInput.isBlank()) return@IconButton
                    scope.launch(Dispatchers.IO) {
                        var personName = ""
                        if (textInput.contains("به ")) personName = textInput.substringAfter("به ").substringBefore(" ")
                        if (textInput.contains("از ")) personName = textInput.substringAfter("از ").substringBefore(" ")
                        if (personName.isBlank()) personName = "ناشناس"

                        var cp = db.counterpartyDao().getByName(personName)
                        if (cp == null) {
                            val id = db.counterpartyDao().insert(Counterparty(name = personName))
                            cp = Counterparty(id = id, name = personName)
                        }
                        
                        val tx = processor.processVoiceText(textInput) { cp.id }
                        if (tx != null) {
                            db.transactionDao().insert(tx.copy(counterpartyId = cp.id))
                        }
                        withContext(Dispatchers.Main) { textInput = "" }
                    }
                }) { Icon(Icons.Default.Add, "ثبت", tint = Color(0xFF00E676)) }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("✏️ ثبت دستی تراکنش (بدون نیاز به ویس)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = manualName, onValueChange = { manualName = it }, label = { Text("نام شخص") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualAmount, onValueChange = { manualAmount = it }, label = { Text("مقدار") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedTextField(value = manualRate, onValueChange = { manualRate = it }, label = { Text("نرخ واحد") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = manualCurrency, onValueChange = { manualCurrency = it }, label = { Text("ارز (دلار/تومان)") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { manualType = if(manualType == "SELL") "BUY" else "SELL" }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                        Text(if(manualType == "SELL") "نوع: فروش" else "نوع: خرید")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val amount = manualAmount.toDoubleOrNull() ?: 0.0
                        val rate = manualRate.toDoubleOrNull() ?: 1.0
                        if (manualName.isBlank() || amount <= 0) return@Button
                        
                        scope.launch(Dispatchers.IO) {
                            var cp = db.counterpartyDao().getByName(manualName)
                            if (cp == null) {
                                val id = db.counterpartyDao().insert(Counterparty(name = manualName))
                                cp = Counterparty(id = id, name = manualName)
                            }
                            val newTx = Transaction(
                                type = manualType,
                                counterpartyId = cp.id,
                                amount = amount,
                                currencyType = manualCurrency,
                                exchangeRate = rate,
                                tomanAmount = amount * rate,
                                isDelivered = true,
                                timestamp = System.currentTimeMillis()
                            )
                            db.transactionDao().insert(newTx)
                            withContext(Dispatchers.Main) {
                                manualName = ""; manualAmount = ""; manualRate = ""
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                        Text("ثبت دستی فاکتور", color = Color.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("📑 دفتر روزنامه (لیست معاملات)", color = Color.Gray, fontSize = 14.sp)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(transactions) { tx ->
                val name = counterparties.find { it.id == tx.counterpartyId }?.name ?: "ناشناس"
                TransactionItem(tx, name)
            }
        }
    }
}

@Composable
fun TransactionItem(tx: Transaction, partyName: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("طرف حساب: $partyName", color = Color.White, fontWeight = FontWeight.Bold)
                Text("${tx.amount} ${tx.currencyType} (نرخ: ${tx.exchangeRate})", color = Color.Gray, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                val fmt = NumberFormat.getInstance(Locale.US)
                Text("${fmt.format(tx.tomanAmount)} تومان", color = if(tx.type == "BUY" || tx.type == "PAY_TOMAN") Color(0xFFFF5252) else Color(0xFF00E676), fontWeight = FontWeight.Bold)
                Text(if(tx.isDelivered) "✓ تحویل شده" else "❌ تعهد تحویل", fontSize = 11.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
fun CounterpartiesScreen(db: AppDatabase) {
    var list by remember { mutableStateOf(listOf<Counterparty>()) }
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }

    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { list = it }
    }
    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("👥 دفتر معین تومانی اشخاص", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        items(list) { cp ->
            val personTxs = transactions.filter { it.counterpartyId == cp.id }
            var totalToman = 0.0
            personTxs.forEach { tx ->
                if (tx.type == "SELL" || tx.type == "RECEIVE_TOMAN") totalToman += tx.tomanAmount
                else if (tx.type == "BUY" || tx.type == "PAY_TOMAN") totalToman -= tx.tomanAmount
            }

            val statusText = if (totalToman > 0) "بدهکار (Debtor)" else if (totalToman < 0) "بستانکار (Creditor)" else "تراز صفر"
            val statusColor = if (totalToman > 0) Color(0xFFFF5252) else if (totalToman < 0) Color(0xFF00E676) else Color.Gray
            val fmt = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت حساب:", color = Color.Gray)
                        Text("$statusText: ${fmt.format(abs(totalToman))} تومان", color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
