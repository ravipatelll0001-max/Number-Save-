package com.example.numbersave

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Screen() } }
    }
}

// ---------- नाम: 001A, 002B, 003C ... 026Z, 027A, 028B ... (क्रम सही रहे इसलिए गिनती पहले) ----------
fun nameFor(i: Int) = String.format(Locale.US, "%03d%c", i + 1, 'A' + (i % 26))

// ---------- नंबर पहचानना ----------
// आगे 91 / +91 / 0 हो या न हो, फिर 6-9 से शुरू होने वाले 10 अंक
private val phoneRegex = Regex("""(?:(?:\+|00)?91[ -]*)?0?[6-9](?:[ -]*\d){9}""")

// सिर्फ़ साफ़ 10 अंक वापस करता है (देश का कोड हटाकर)
fun extractNumbers(text: String): List<String> =
    text.lines().flatMap { line ->
        phoneRegex.findAll(line).mapNotNull { m ->
            var d = m.value.filter(Char::isDigit)
            if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
            else if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
            if (d.length == 10) d else null
        }.toList()
    }

fun isValid(n: String) = n.length == 10 && n[0] in '6'..'9'

// ---------- स्कैन (एक या कई फोटो, एक-एक करके) ----------
fun scanImages(ctx: Context, uris: List<Uri>, onDone: (List<String>, Int) -> Unit) {
    val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val found = mutableListOf<String>()
    var failed = 0
    fun next(i: Int) {
        if (i >= uris.size) {
            onDone(found, failed)
            return
        }
        try {
            rec.process(InputImage.fromFilePath(ctx, uris[i]))
                .addOnSuccessListener { found.addAll(extractNumbers(it.text)); next(i + 1) }
                .addOnFailureListener { failed++; next(i + 1) }
        } catch (e: Exception) {
            failed++
            next(i + 1)
        }
    }
    next(0)
}

// ---------- PDF स्कैन (हर पन्ना फोटो बनाकर पढ़ा जाता है) ----------
fun scanPdfs(
    ctx: Context,
    uris: List<Uri>,
    onProgress: (String) -> Unit,
    onDone: (List<String>, Int) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found = mutableListOf<String>()
        var failed = 0
        for (uri in uris) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    failed++
                    continue
                }
                val renderer = PdfRenderer(pfd)
                try {
                    val total = renderer.pageCount
                    for (p in 0 until total) {
                        main.post { onProgress("PDF पन्ना ${p + 1} / $total पढ़ा जा रहा है...") }
                        val page = renderer.openPage(p)
                        try {
                            val scale = maxOf(1f, 2000f / page.width)
                            val w = (page.width * scale).toInt()
                            val h = (page.height * scale).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            try {
                                val res = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
                                found.addAll(extractNumbers(res.text))
                            } catch (e: Exception) {
                                failed++
                            }
                            bmp.recycle()
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                failed++
            }
        }
        main.post { onDone(found, failed) }
    }.start()
}

// ---------- VCF ----------
fun vcfText(numbers: List<String>, offset: Int): String {
    val sb = StringBuilder()
    numbers.forEachIndexed { k, n ->
        val name = nameFor(offset + k)
        sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\nN:;$name;;;\r\nFN:$name\r\nTEL;TYPE=CELL:$n\r\nEND:VCARD\r\n")
    }
    return sb.toString()
}

// Downloads/NumberSave/<तारीख_समय>/ में फाइलें सेव करता है
fun saveVcfFiles(ctx: Context, numbers: List<String>, batch: Int): Pair<String, List<Uri>> {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val folder = "NumberSave/$stamp"
    val chunks = numbers.chunked(maxOf(1, batch))
    val uris = mutableListOf<Uri>()
    chunks.forEachIndexed { idx, chunk ->
        val fileName =
            if (chunks.size == 1) "contacts.vcf" else "contacts_${idx + 1}_of_${chunks.size}.vcf"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/x-vcard")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw Exception("फाइल नहीं बन पाई")
        ctx.contentResolver.openOutputStream(uri)!!.use {
            it.write(vcfText(chunk, idx * maxOf(1, batch)).toByteArray(Charsets.UTF_8))
        }
        uris.add(uri)
    }
    return Pair("Download/$folder", uris)
}

fun shareFiles(ctx: Context, uris: List<Uri>) {
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/x-vcard"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "VCF भेजें"))
}

// ---------- सीधा SIM में सेव (कई फोन ये रोक देते हैं) ----------
fun saveToSim(ctx: Context, numbers: List<String>, onDone: (Int) -> Unit) {
    Thread {
        var ok = 0
        var fails = 0
        for ((i, n) in numbers.withIndex()) {
            try {
                val cv = ContentValues().apply {
                    put("tag", nameFor(i))
                    put("number", n)
                }
                if (ctx.contentResolver.insert(Uri.parse("content://icc/adn"), cv) != null) {
                    ok++
                    fails = 0
                } else fails++
            } catch (e: Exception) {
                fails++
            }
            if (fails >= 3) break
        }
        Handler(Looper.getMainLooper()).post { onDone(ok) }
    }.start()
}

// ---------- स्क्रीन ----------
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("numbersave", Context.MODE_PRIVATE) }
    val numbers = remember {
        mutableStateListOf<String>().apply {
            addAll((prefs.getString("n", "") ?: "").split(",").filter { it.isNotBlank() })
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { numbers.toList() }.collect {
            prefs.edit().putString("n", it.joinToString(",")).apply()
        }
    }

    var status by remember { mutableStateOf("पेज की फोटो खींचिए या गैलरी से चुनिए") }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var batch by rememberSaveable { mutableStateOf(200) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var lastUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun finishScan(found: List<String>, failed: Int) {
        val new = found.distinct().filter { it !in numbers }
        numbers.addAll(new)
        busy = false
        status = "${new.size} नए नंबर मिले, कुल ${numbers.size}" +
            if (failed > 0) " ($failed हिस्से पढ़े नहीं गए)" else ""
    }

    fun handleScan(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        status = "स्कैन हो रहा है..."
        scanImages(ctx, uris) { found, failed -> finishScan(found, failed) }
    }

    fun handlePdf(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        status = "PDF खोली जा रही है..."
        scanPdfs(ctx, uris, { msg -> status = msg }) { found, failed -> finishScan(found, failed) }
    }

    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { handleScan(listOf(it)) }
    }
    val pickPics = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handleScan(uris)
    }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handlePdf(uris)
    }
    val simPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.WRITE_CONTACTS] == true) {
            busy = true
            status = "SIM में लिखा जा रहा है..."
            val list = numbers.toList()
            saveToSim(ctx, list) { ok ->
                busy = false
                status = when {
                    ok == 0 -> "SIM में एक भी नंबर नहीं लिखा जा सका। फोन ने रोक दिया है। VCF वाला तरीका इस्तेमाल करें।"
                    ok < list.size -> "$ok / ${list.size} नंबर SIM में सेव हुए (SIM भर गई या फोन ने रोका)"
                    else -> "$ok नंबर SIM में सेव हुए"
                }
            }
        } else status = "Contacts की इजाज़त चाहिए"
    }

    fun doSave(b: Int) {
        try {
            val (path, uris) = saveVcfFiles(ctx, numbers.toList(), b)
            lastUris = uris
            status = "${uris.size} फाइल सेव हुई: $path"
        } catch (e: Exception) {
            status = "फाइल सेव नहीं हो पाई: ${e.message}"
        }
    }

    val allValid = numbers.isNotEmpty() && numbers.all { isValid(it) }
    val files = if (numbers.isEmpty()) 0 else (numbers.size + batch - 1) / batch

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("सब नंबर हटाएँ?") },
            text = { Text("लिस्ट के सारे ${numbers.size} नंबर हट जाएँगे।") },
            confirmButton = {
                TextButton(onClick = {
                    numbers.clear()
                    lastUris = emptyList()
                    status = "लिस्ट खाली"
                    confirmClear = false
                }) { Text("हाँ, हटाओ") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("नहीं") } }
        )
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)
    ) {
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (numbers.isNotEmpty() && !allValid) {
            Text(
                "लाल निशान वाले नंबर ठीक कीजिए (10 अंक, 6-9 से शुरू)",
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                modifier = Modifier.weight(1f), enabled = !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = {
                    val dir = File(ctx.cacheDir, "images").apply { mkdirs() }
                    val f = File(dir, "scan${System.currentTimeMillis()}.jpg")
                    val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
                    photoUri = u
                    takePic.launch(u)
                }
            ) { Text("कैमरा") }
            Button(
                modifier = Modifier.weight(1f), enabled = !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = { pickPics.launch("image/*") }
            ) { Text("गैलरी") }
            Button(
                modifier = Modifier.weight(1f), enabled = !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = { pickPdfs.launch("application/pdf") }
            ) { Text("PDF") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(numbers) { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nameFor(i), Modifier.width(64.dp))
                    OutlinedTextField(
                        value = n,
                        onValueChange = { numbers[i] = it.filter(Char::isDigit).take(10) },
                        modifier = Modifier.weight(1f),
                        isError = !isValid(n),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    TextButton(onClick = { numbers.removeAt(i) }) { Text("X") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "कुल ${numbers.size} नंबर → $files फाइल",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                enabled = numbers.isNotEmpty() && !busy,
                onClick = { confirmClear = true }
            ) { Text("सब हटाओ") }
            Text("हर फाइल में", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("$batch ▼") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(50, 100, 200, 300, 400, 500, 1000).forEach { s ->
                        DropdownMenuItem(text = { Text("$s") }, onClick = { batch = s; menuOpen = false })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                modifier = Modifier.weight(1f), enabled = allValid && !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = { doSave(numbers.size) }
            ) { Text("एक बड़ी VCF") }
            Button(
                modifier = Modifier.weight(1f), enabled = allValid && !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = { doSave(batch) }
            ) { Text("बैच VCF") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f), enabled = lastUris.isNotEmpty() && !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = { shareFiles(ctx, lastUris) }
            ) { Text("भेजें (ब्लूटूथ)") }
            OutlinedButton(
                modifier = Modifier.weight(1f), enabled = allValid && !busy,
                contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = {
                    simPerm.launch(
                        arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                    )
                }
            ) { Text("SIM में सेव") }
        }
    }
}
