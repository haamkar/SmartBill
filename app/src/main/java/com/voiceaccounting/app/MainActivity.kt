package com.voiceaccounting.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                            onClick = { /* در نسخه‌های بعدی: اتصال به لایه ضبط صدای واقعی */ },
                            containerColor = Color(0xFF00E676),
                            shape = CircleShape
                        ) {
                            Text("🎙️", fontSize = 24.sp)
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212))) {
                        if (selectedTab == 0) JournalScreen(db, voiceProcessor)
                        else CounterpartiesScreen(db)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(db: AppDatabase, processor: VoiceProcessor) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var textInput by remember { mutableStateOf("") }
    var counterparties by remember { mutableStateOf(listOf<Counterparty>()) }

    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }
    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { counterparties = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("🎙️ دستور صوتی (شبیه‌ساز متنی)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("مثلاً: فروختم ۱۰۰۰ دلار در ۱۵۵ به جعفر تحویل نشد") },
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
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("📑 معاملات ثبت شده امروز", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text(if(tx.isDelivered) "✓ تحویل شد" else "❌ تعهد تحویل", fontSize = 11.sp, color = Color.LightGray)
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
        item { Text("👥 وضعیت معین و تراز طرف حساب‌ها", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        items(list) { cp ->
            // محاسبه هوشمند ریاضی تراز تومانی هر شخص بر اساس استاندارد سند SRS
            val personTxs = transactions.filter { it.counterpartyId == cp.id }
            var totalToman = 0.0
            personTxs.forEach { tx ->
                if (tx.type == "SELL" || tx.type == "RECEIVE_TOMAN") {
                    totalToman += tx.tomanAmount // او به ما بدهکار می‌شود یا دریافتی ماست
                } else if (tx.type == "BUY" || tx.type == "PAY_TOMAN") {
                    totalToman -= tx.tomanAmount // ما به او بدهکار می‌شویم
                }
            }

            val statusText = if (totalToman > 0) "بدهکار (Debtor)" else if (totalToman < 0) "بستانکار (Creditor)" else "تراز صفر"
            val statusColor = if (totalToman > 0) Color(0xFFFF5252) else if (totalToman < 0) Color(0xFF00E676) else Color.Gray
            val fmt = NumberFormat.getInstance(Locale.US)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت تراز مالی:", color = Color.Gray)
                        Text("$statusText: ${fmt.format(abs(totalToman))} تومان", color = statusColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
