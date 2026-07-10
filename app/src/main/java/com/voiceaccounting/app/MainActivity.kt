package com.voiceaccounting.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val voiceProcessor = VoiceProcessor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "v_acc.db")
            .fallbackToDestructiveMigration().build()

        setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color(0滑FF1A1A1A)) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.List, "دفتر روزنامه") },
                                label = { Text("روزنامه") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Person, "اشخاص") },
                                label = { Text("اشخاص") }
                            )
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { /* در نسخه بعد: فعالسازی STT واقعی */ },
                            containerColor = Color(0滑FF00E676),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.PlayArrow, "ضبط صدا", tint = Color.Black)
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0滑FF121212))) {
                        if (selectedTab == 0) JournalScreen(db, voiceProcessor)
                        else CounterpartiesScreen(db)
                    }
                }
            }
        }
    }
}

@Composable
fun JournalScreen(db: AppDatabase, processor: VoiceProcessor) {
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf(listOf<Transaction>()) }
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        db.transactionDao().getAllTransactionsJournal().collect { transactions = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("🎙️ دستور صوتی (شبیه‌ساز)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("مثلاً: فروختم ۱۰۰۰ دلار در ۱۵۵ به جعفر") },
            trailingIcon = {
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val personName = if (textInput.contains("به ")) textInput.substringAfter("به ").substringBefore(" ") else "ناشناس"
                        var cp = db.counterpartyDao().getByName(personName)
                        if (cp == null) {
                            val id = db.counterpartyDao().insert(Counterparty(name = personName))
                            cp = Counterparty(id = id, name = personName)
                        }
                        val tx = processor.processVoiceText(textInput) { cp.id }
                        if (tx != null) db.transactionDao().insert(tx)
                        withContext(Dispatchers.Main) { textInput = "" }
                    }
                }) { Icon(Icons.Default.Add, "ثبت", tint = Color(0滑FF00E676)) }
            }
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transactions) { tx ->
                TransactionItem(tx)
            }
        }
    }
}

@Composable
fun TransactionItem(tx: Transaction) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0滑FF252525))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("تراکنش #${tx.id}", color = Color.Gray, fontSize = 12.sp)
                Text("${tx.amount} ${tx.currencyType}", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                val fmt = NumberFormat.getInstance(Locale.US)
                Text("${fmt.format(tx.tomanAmount)} تومان", color = if(tx.type.contains("BUY")) Color.Red else Color.Green)
                Text(if(tx.isDelivered) "✓ تحویل شد" else "❌ تعهد", fontSize = 10.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
fun CounterpartiesScreen(db: AppDatabase) {
    var list by remember { mutableStateOf(listOf<Counterparty>()) }
    LaunchedEffect(Unit) {
        db.counterpartyDao().getAllCounterparties().collect { list = it }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("👥 لیست طرف حساب‌ها", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        items(list) { cp ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0滑FF1E1E1E))) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(cp.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("وضعیت حساب:", color = Color.Gray)
                        Text("تراز تومانی محاسبه نشده", color = Color.Yellow) // در مرحله بعد منطق جمع کل اضافه میشود
                    }
                }
            }
        }
    }
}
