/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.element.android.compound.theme.ElementTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Conversation ouverte, avec un brouillon éventuel. */
private data class OpenThread(val info: SmsThreadInfo, val draft: String? = null)

@Composable
internal fun SmsApp(target: SmsTarget?) {
    val context = LocalContext.current
    var openThread by remember { mutableStateOf<OpenThread?>(null) }

    // Afficher le nom des contacts plutôt que leur numéro : demandé une fois par ouverture tant que ce n'est pas accordé.
    var contactsGranted by remember { mutableIntStateOf(0) }
    var askedContacts by rememberSaveable { mutableStateOf(false) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) contactsGranted++ // recharge pour afficher les noms
    }
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted && !askedContacts) {
            askedContacts = true
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // Augmente à chaque changement dans la base SMS (nouveau SMS…) ou à l'accord des contacts : déclenche un rechargement.
    val version = rememberSmsChanges(context) + contactsGranted

    // Ouvre une conversation par son fil (notification) ou un numéro ("Envoyer un SMS" depuis une autre app, bouton +).
    val scope = rememberCoroutineScope()
    fun open(threadId: Long?, address: String?, draft: String? = null) {
        scope.launch {
            openThread = withContext(Dispatchers.IO) {
                val info = when {
                    threadId != null -> SmsRepository.threadInfo(context, threadId)
                    address != null -> SmsRepository.threadInfo(context, address)
                    else -> null
                }
                info?.let { OpenThread(it, draft) }
            }
        }
    }
    LaunchedEffect(target) {
        target?.let { open(it.threadId, it.address, it.draft) }
    }

    val thread = openThread
    if (thread == null) {
        ConversationListScreen(
            version = version,
            onOpen = { openThread = OpenThread(SmsThreadInfo(it.threadId, it.addresses, it.displayName)) },
            onNewConversation = { open(threadId = null, address = it) },
        )
    } else {
        BackHandler { openThread = null }
        ThreadScreen(thread = thread, version = version, onBack = { openThread = null })
    }
}

/** Compteur qui augmente à chaque modification de la base SMS/MMS. */
@Composable
private fun rememberSmsChanges(context: Context): Int {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                version++
            }
        }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return version
}

// ── Liste des conversations ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(version: Int, onOpen: (SmsConversation) -> Unit, onNewConversation: (String) -> Unit) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<SmsConversation>?>(null) }
    var showNewConversation by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        conversations = withContext(Dispatchers.IO) { SmsRepository.loadConversations(context) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SMS", style = ElementTheme.typography.fontHeadingSmMedium) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewConversation = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle conversation")
            }
        },
        containerColor = ElementTheme.colors.bgCanvasDefault,
    ) { padding ->
        val list = conversations
        when {
            list == null -> Unit // chargement
            list.isEmpty() -> CenteredText(
                "Aucun SMS.\nSi tu en as, vérifie que Pigeon est l'app SMS par défaut.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.threadId }) { conversation ->
                    ConversationRow(conversation, onClick = { onOpen(conversation) })
                    HorizontalDivider(color = ElementTheme.colors.separatorPrimary)
                }
            }
        }
    }

    if (showNewConversation) {
        NewConversationDialog(
            onDismiss = { showNewConversation = false },
            onConfirm = { number ->
                showNewConversation = false
                onNewConversation(number)
            },
        )
    }
}

@Composable
private fun NewConversationDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle conversation") },
        text = {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Numéro de téléphone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(number.trim()) }, enabled = number.isNotBlank()) { Text("Ouvrir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ConversationRow(conversation: SmsConversation, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.displayName,
                style = if (unread) ElementTheme.typography.fontBodyLgMedium else ElementTheme.typography.fontBodyLgRegular,
                color = ElementTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (conversation.lastIsOutgoing) "Vous : ${conversation.snippet}" else conversation.snippet,
                style = ElementTheme.typography.fontBodyMdRegular,
                color = if (unread) ElementTheme.colors.textPrimary else ElementTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDate(conversation.date),
                style = ElementTheme.typography.fontBodySmRegular,
                color = if (unread) ElementTheme.colors.textActionAccent else ElementTheme.colors.textSecondary,
            )
            if (unread) {
                Spacer(Modifier.size(4.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(ElementTheme.colors.bgAccentRest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = conversation.unreadCount.coerceAtMost(99).toString(),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textOnSolidPrimary,
                    )
                }
            }
        }
    }
}

// ── Fil d'une conversation ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadScreen(thread: OpenThread, version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = thread.info
    var messages by remember(info.threadId) { mutableStateOf<List<SmsItem>>(emptyList()) }
    var text by rememberSaveable(info.threadId) { mutableStateOf(thread.draft.orEmpty()) }
    var fullScreenImage by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(info.threadId, version) {
        messages = withContext(Dispatchers.IO) {
            SmsRepository.markThreadRead(context, info.threadId)
            SmsRepository.loadMessages(context, info.threadId, info.isGroup)
        }
        // La conversation est lue : on retire sa notification.
        NotificationManagerCompat.from(context).cancel(SmsNotifier.notificationId(info.threadId))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        scope.launch {
            val started = withContext(Dispatchers.IO) { SmsSender.send(context, info.addresses.first(), body) }
            if (!started) {
                Toast.makeText(context, "Envoi impossible : Pigeon doit être l'app SMS par défaut.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info.title, style = ElementTheme.typography.fontHeadingSmMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            when {
                // L'envoi aux groupes passe par un MMS : prochaine étape.
                info.isGroup -> BottomNotice("Répondre à un groupe n'est pas encore possible.")
                info.addresses.isEmpty() -> BottomNotice("Correspondant inconnu.")
                else -> Composer(text = text, onTextChange = { text = it }, onSend = ::send)
            }
        },
        containerColor = ElementTheme.colors.bgCanvasDefault,
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(messages, key = { it.key }) { MessageBubble(it, onImageClick = { uri -> fullScreenImage = uri }) }
        }
    }

    fullScreenImage?.let { uri ->
        Dialog(onDismissRequest = { fullScreenImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullScreenImage = null },
                contentAlignment = Alignment.Center,
            ) {
                MmsImage(uri, maxSize = 2048, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BottomNotice(text: String) {
    Text(
        text = text,
        style = ElementTheme.typography.fontBodyMdRegular,
        color = ElementTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
    )
}

/** Zone de saisie, qui remonte au-dessus du clavier. */
@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCanvasDefault)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("SMS") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
        }
    }
}

@Composable
private fun MessageBubble(message: SmsItem, onImageClick: (Uri) -> Unit) {
    val incoming = message.isIncoming
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (incoming) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (incoming) ElementTheme.colors.bgSubtleSecondary else ElementTheme.colors.bgAccentRest,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val textColor = if (incoming) ElementTheme.colors.textPrimary else ElementTheme.colors.textOnSolidPrimary
            message.sender?.let {
                Text(text = it, style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textActionAccent)
            }
            for (attachment in message.attachments) {
                if (attachment.isImage) {
                    MmsImage(
                        uri = attachment.uri,
                        maxSize = 1080,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(attachment.uri) },
                    )
                } else {
                    Text(text = "📎 Pièce jointe (${attachment.contentType})", style = ElementTheme.typography.fontBodyMdRegular, color = textColor)
                }
            }
            if (message.body.isNotBlank()) {
                Text(text = message.body, style = ElementTheme.typography.fontBodyLgRegular, color = textColor)
            }
            Text(
                text = when (message.status) {
                    SmsStatus.SENDING -> "Envoi…"
                    SmsStatus.FAILED -> "Échec de l'envoi"
                    else -> formatDate(message.date)
                },
                style = ElementTheme.typography.fontBodySmRegular,
                color = when {
                    message.status == SmsStatus.FAILED -> ElementTheme.colors.textCriticalPrimary
                    incoming -> ElementTheme.colors.textSecondary
                    else -> ElementTheme.colors.textOnSolidPrimary
                },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Photo d'un MMS, réduite à [maxSize] pixels au plus pour ne pas saturer la mémoire. */
@Composable
private fun MmsImage(uri: Uri, maxSize: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, maxSize) {
        value = withContext(Dispatchers.IO) { decodeImage(context, uri, maxSize) }
    }
    val image = bitmap
    if (image == null) {
        Box(modifier = modifier.size(width = 220.dp, height = 160.dp).background(ElementTheme.colors.bgSubtlePrimary))
    } else {
        Image(bitmap = image, contentDescription = "Photo", contentScale = ContentScale.FillWidth, modifier = modifier)
    }
}

private fun decodeImage(context: Context, uri: Uri, maxSize: Int): ImageBitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Tient compte de l'orientation de la photo.
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > maxSize) decoder.setTargetSampleSize(largest / maxSize)
        }.asImageBitmap()
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxSize) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    }
} catch (e: Exception) {
    Timber.w(e, "Photo du MMS illisible")
    null
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
    }
}

/** "14:32" aujourd'hui, sinon la date. */
@Composable
private fun formatDate(timestamp: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(timestamp)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, timestamp, flags)
}
