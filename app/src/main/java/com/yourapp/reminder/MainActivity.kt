package com.yourapp.reminder

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

// ==================== БД ====================
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: Note)
    @Query("SELECT * FROM notes ORDER BY timestamp ASC") 
    fun getAll(): kotlinx.coroutines.flow.Flow<List<Note>>
    @Query("DELETE FROM notes WHERE id = :id") 
    suspend fun delete(id: Int)
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NoteDao
    companion object {
        fun get(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "db").build()
    }
}

// ==================== БУДИЛЬНИК ====================
object AlarmScheduler {
    fun set(context: Context, noteId: Int, title: String, time: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("id", noteId)
            putExtra("title", title)
        }
        val pi = PendingIntent.getBroadcast(context, noteId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }

    fun cancel(context: Context, noteId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(context, noteId, 
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        pi.cancel()
    }
}

// ==================== РЕСИВЕР ====================
class AlarmReceiver : BroadcastReceiver() {
    private lateinit var tts: TextToSpeech

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val title = intent.getStringExtra("title") ?: "Напоминание"

        val ringtone = RingtoneManager.getRingtone(context, 
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        repeat(3) {
            ringtone?.play()
            Thread.sleep(1000)
            ringtone?.stop()
            Thread.sleep(200)
        }

        ContextCompat.getSystemService(context, Vibrator::class.java)?.vibrate(1000)

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("ru")
                tts.speak(title, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (id != -1) AppDatabase.get(context).dao().delete(id)
        }
    }
}

// ==================== VIEWMODEL ====================
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items

    init {
        viewModelScope.launch {
            db.dao().getAll().collect { _items.value = it }
        }
    }

    fun add(note: Note) = viewModelScope.launch { db.dao().insert(note) }
    fun delete(id: Int) = viewModelScope.launch { db.dao().delete(id) }
}

// ==================== MAIN ACTIVITY ====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme())
            MainScreen()
        }
    }
}

// ==================== UI ====================
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val notes by vm.items.collectAsState()
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Text("+", fontSize = MaterialTheme.typography.headlineLarge.fontSize)
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes) { note ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            note.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 3
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                    .format(Date(note.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Button(
                                onClick = {
                                    AlarmScheduler.cancel(ctx, note.id)
                                    vm.delete(note.id)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("✕", fontSize = MaterialTheme.typography.labelLarge.fontSize)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var cal by remember { mutableStateOf(Calendar.getInstance()) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showTimePicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { 
                Text(
                    "Новая заметка",
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название (озвучивается)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Текст (не озвучивается)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            showDatePicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("📅 ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(cal.time)}")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank() && content.isNotBlank()) {
                            val note = Note(
                                title = title,
                                content = content,
                                timestamp = cal.timeInMillis
                            )
                            vm.add(note)
                            AlarmScheduler.set(ctx, note.id, title, cal.timeInMillis)
                            showDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Отмена")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )

        if (showDatePicker) {
            DatePickerDialog(
                ctx,
                { _: DatePicker, year: Int, month: Int, day: Int ->
                    cal.set(year, month, day)
                    showDatePicker = false
                    showTimePicker = true
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        if (showTimePicker) {
            TimePickerDialog(
                ctx,
                { _: TimePicker, hour: Int, minute: Int ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    showTimePicker = false
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        }
    }
}
