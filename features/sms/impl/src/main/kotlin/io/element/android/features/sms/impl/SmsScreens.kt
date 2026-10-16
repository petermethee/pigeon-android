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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.TextFieldDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Conversation ouverte, avec un brouillon éventuel. */
private data class OpenThread(val info: SmsThreadInfo, val draft: String? = null)

/** Garde la conversation ouverte quand l'écran est recréé (rotation, thème…). */
private val OpenThreadSaver = listSaver<OpenThread?, Any?>(
    save = { thread ->
        if (thread == null) emptyList() else listOf(thread.info.threadId, ArrayList(thread.info.addresses), thread.info.title, thread.draft)
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            OpenThread(
                info = SmsThreadInfo(saved[0] as Long, (saved[1] as List<*>).map { it as String }, saved[2] as String),
                draft = saved[3] as String?,
            )
        }
    },
)

/**
 * Écran des SMS : liste des conversations, puis fil d'une conversation.
 *
 * @param target conversation à ouvrir (notification, "Envoyer un SMS" depuis une autre app), une seule fois par demande.
 * @param resumed l'écran est au premier plan : la conversation ouverte ne reçoit alors pas de notification.
 */
@Composable
internal fun SmsApp(target: SmsTarget?, resumed: Boolean) {
    val context = LocalContext.current
    var openThread by rememberSaveable(stateSaver = OpenThreadSaver) { mutableStateOf<OpenThread?>(null) }

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

    // Son propre numéro, demandé une seule fois s'il n'a pas pu être trouvé (pour les MMS de groupe).
    var askSelfNumber by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        askSelfNumber = withContext(Dispatchers.IO) { !SelfNumbers.wasAsked(context) && !SelfNumbers.isKnown(context) }
    }
    if (askSelfNumber) {
        SelfNumberDialog(
            onSubmit = { number ->
                SelfNumbers.remember(context, number.trim())
                SelfNumbers.setAsked(context)
                askSelfNumber = false
            },
            onDismiss = {
                SelfNumbers.setAsked(context)
                askSelfNumber = false
            },
        )
    }

    // Augmente à chaque changement dans la base SMS/MMS (nouveau message…) ou à l'accord des contacts : déclenche un rechargement.
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
        ThreadScreen(thread = thread, version = version, resumed = resumed, onBack = { openThread = null })
    }
}

/**
 * Compteur qui augmente à chaque modification de la base SMS/MMS.
 *
 * Un même changement est signalé sur plusieurs adresses (SMS, MMS, conversations) : les signaux rapprochés
 * sont regroupés en un seul rechargement.
 */
@Composable
private fun rememberSmsChanges(context: Context): Int {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val bump = Runnable { version++ }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(bump)
                handler.postDelayed(bump, CHANGES_DEBOUNCE_MS)
            }
        }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
        onDispose {
            handler.removeCallbacks(bump)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return version
}

private const val CHANGES_DEBOUNCE_MS = 300L

@Composable
private fun SelfNumberDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    TextFieldDialog(
        title = stringResource(R.string.screen_sms_self_number_title),
        content = stringResource(R.string.screen_sms_self_number_text),
        onSubmit = onSubmit,
        onDismissRequest = onDismiss,
        value = null,
        placeholder = "+33 6 12 34 56 78",
        validation = { !it.isNullOrBlank() },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}

// ── Liste des conversations ─────────────────────────────────────────────────────

@Composable
private fun ConversationListScreen(version: Int, onOpen: (SmsConversation) -> Unit, onNewConversation: (String) -> Unit) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<SmsConversation>?>(null) }
    LaunchedEffect(version) {
        conversations = withContext(Dispatchers.IO) { SmsRepository.loadConversations(context) }
    }
    ConversationListView(conversations = conversations, onOpen = onOpen, onNewConversation = onNewConversation)
}

/** Liste des conversations ; [conversations] vaut null pendant le chargement. */
@Composable
private fun ConversationListView(
    conversations: List<SmsConversation>?,
    onOpen: (SmsConversation) -> Unit,
    onNewConversation: (String) -> Unit,
) {
    var showNewConversation by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(titleStr = stringResource(R.string.screen_sms_list_title)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewConversation = true }) {
                Icon(CompoundIcons.Plus(), contentDescription = stringResource(R.string.screen_sms_new_conversation))
            }
        },
    ) { padding ->
        when {
            conversations == null -> Unit // chargement
            conversations.isEmpty() -> CenteredText(stringResource(R.string.screen_sms_list_empty), Modifier.padding(padding))
            else -> LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.threadId }) { conversation ->
                    ConversationRow(conversation, onClick = { onOpen(conversation) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewConversation) {
        TextFieldDialog(
            title = stringResource(R.string.screen_sms_new_conversation),
            onSubmit = { number ->
                showNewConversation = false
                onNewConversation(number.trim())
            },
            onDismissRequest = { showNewConversation = false },
            value = null,
            placeholder = stringResource(R.string.screen_sms_new_conversation_number),
            validation = { !it.isNullOrBlank() },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
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
                text = if (conversation.lastIsOutgoing) {
                    stringResource(R.string.screen_sms_last_message_outgoing, conversation.snippet)
                } else {
                    conversation.snippet
                },
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

@Composable
private fun ThreadScreen(thread: OpenThread, version: Int, resumed: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = thread.info
    var messages by remember(info.threadId) { mutableStateOf<List<SmsItem>>(emptyList()) }
    var text by rememberSaveable(info.threadId) { mutableStateOf(thread.draft.orEmpty()) }

    LaunchedEffect(info.threadId, version) {
        messages = withContext(Dispatchers.IO) {
            SmsRepository.markThreadRead(context, info.threadId)
            SmsRepository.loadMessages(context, info.threadId, info.isGroup)
        }
    }
    // Conversation à l'écran : ses nouveaux messages ne font pas de notification, et la sienne est retirée.
    DisposableEffect(info.threadId, resumed) {
        if (resumed) {
            SmsNotifier.visibleThreadId = info.threadId
            NotificationManagerCompat.from(context).cancel(SmsNotifier.notificationId(info.threadId))
        }
        onDispose {
            if (SmsNotifier.visibleThreadId == info.threadId) SmsNotifier.visibleThreadId = null
        }
    }

    val notDefaultApp = stringResource(R.string.screen_sms_error_not_default_app)
    ThreadView(
        info = info,
        messages = messages,
        text = text,
        onTextChange = { text = it },
        onSend = {
            val body = text.trim()
            if (body.isNotEmpty()) {
                text = ""
                scope.launch {
                    val started = withContext(Dispatchers.IO) { SmsSender.send(context, info.addresses.first(), body) }
                    if (!started) Toast.makeText(context, notDefaultApp, Toast.LENGTH_LONG).show()
                }
            }
        },
        onRetryMms = { pendingId -> scope.launch(Dispatchers.IO) { MmsDownloader.retry(context, pendingId) } },
        onBack = onBack,
    )
}

@Composable
private fun ThreadView(
    info: SmsThreadInfo,
    messages: List<SmsItem>,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetryMms: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var fullScreenImage by remember { mutableStateOf<Uri?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info.title, style = ElementTheme.typography.aliasScreenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
        bottomBar = {
            when {
                // L'envoi aux groupes passe par un MMS : prochaine étape.
                info.isGroup -> BottomNotice(stringResource(R.string.screen_sms_group_reply_unavailable))
                info.addresses.isEmpty() -> BottomNotice(stringResource(R.string.screen_sms_unknown_recipient))
                else -> Composer(text = text, onTextChange = onTextChange, onSend = onSend)
            }
        },
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
            items(messages, key = { it.key }) { message ->
                MessageBubble(message, onImageClick = { fullScreenImage = it }, onRetryMms = onRetryMms)
            }
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
        TextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = stringResource(R.string.screen_sms_composer_placeholder),
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(CompoundIcons.Send(), contentDescription = stringResource(CommonStrings.action_send))
        }
    }
}

@Composable
private fun MessageBubble(message: SmsItem, onImageClick: (Uri) -> Unit, onRetryMms: (Long) -> Unit) {
    val incoming = message.isIncoming
    val pending = message.pendingMms
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (incoming) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (incoming) ElementTheme.colors.bgSubtleSecondary else ElementTheme.colors.bgAccentRest,
                    shape = RoundedCornerShape(16.dp),
                )
                .clip(RoundedCornerShape(16.dp))
                .then(if (pending != null) Modifier.clickable { onRetryMms(pending.id) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val textColor = if (incoming) ElementTheme.colors.textPrimary else ElementTheme.colors.textOnSolidPrimary
            message.sender?.let {
                Text(text = it, style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textActionAccent)
            }
            if (pending != null) {
                Text(
                    text = stringResource(if (pending.downloading) R.string.screen_sms_mms_downloading else R.string.screen_sms_mms_download_failed),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = if (pending.downloading) ElementTheme.colors.textSecondary else ElementTheme.colors.textCriticalPrimary,
                )
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
                    Text(
                        text = stringResource(R.string.screen_sms_attachment, attachment.contentType),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = textColor,
                    )
                }
            }
            if (message.body.isNotBlank()) {
                Text(text = message.body, style = ElementTheme.typography.fontBodyLgRegular, color = textColor)
            }
            Text(
                text = when (message.status) {
                    SmsStatus.SENDING -> stringResource(R.string.screen_sms_status_sending)
                    SmsStatus.FAILED -> stringResource(R.string.screen_sms_status_failed)
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
    val inPreview = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, maxSize) {
        if (!inPreview) value = withContext(Dispatchers.IO) { decodeImage(context, uri, maxSize) }
    }
    val image = bitmap
    if (image == null) {
        Box(modifier = modifier.size(width = 220.dp, height = 160.dp).background(ElementTheme.colors.bgSubtlePrimary))
    } else {
        Image(bitmap = image, contentDescription = stringResource(R.string.screen_sms_photo), contentScale = ContentScale.FillWidth, modifier = modifier)
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

// ── Aperçus ─────────────────────────────────────────────────────────────────────

private const val PREVIEW_NOW = 1_760_000_000_000L

internal class ConversationListPreviewParam : PreviewParameterProvider<List<SmsConversation>?> {
    override val values = sequenceOf(
        listOf(
            SmsConversation(1, listOf("+33611111111"), "Alice", "À demain !", lastIsOutgoing = false, date = PREVIEW_NOW, unreadCount = 2),
            SmsConversation(2, listOf("+33622222222"), "Bob", "Ok, merci", lastIsOutgoing = true, date = PREVIEW_NOW - 86_400_000, unreadCount = 0),
            SmsConversation(3, listOf("+33611111111", "+33633333333"), "Alice, Carole", "📷 Photo", lastIsOutgoing = false, date = PREVIEW_NOW, unreadCount = 0),
        ),
        emptyList(),
        null,
    )
}

@PreviewsDayNight
@Composable
internal fun ConversationListViewPreview(@PreviewParameter(ConversationListPreviewParam::class) conversations: List<SmsConversation>?) = ElementPreview {
    ConversationListView(conversations = conversations, onOpen = {}, onNewConversation = {})
}

internal class ThreadPreviewParam : PreviewParameterProvider<Pair<SmsThreadInfo, List<SmsItem>>> {
    override val values = sequenceOf(
        SmsThreadInfo(1, listOf("+33611111111"), "Alice") to listOf(
            SmsItem("sms-1", "Salut, tu viens ce soir ?", PREVIEW_NOW - 60_000, SmsStatus.RECEIVED),
            SmsItem("sms-2", "Oui, vers 20 h", PREVIEW_NOW - 30_000, SmsStatus.SENT),
            SmsItem("sms-3", "Je prends le pain", PREVIEW_NOW, SmsStatus.SENDING),
            SmsItem("sms-4", "Message non parti", PREVIEW_NOW, SmsStatus.FAILED),
        ),
        SmsThreadInfo(3, listOf("+33611111111", "+33633333333"), "Alice, Carole") to listOf(
            SmsItem(
                key = "mms-1",
                body = "Regardez !",
                date = PREVIEW_NOW - 60_000,
                status = SmsStatus.RECEIVED,
                sender = "Alice",
                attachments = listOf(MmsAttachment(Uri.EMPTY, "image/jpeg")),
            ),
            SmsItem("mms-2", "", PREVIEW_NOW, SmsStatus.RECEIVED, sender = "Carole", pendingMms = PendingMmsState(2, downloading = false)),
            SmsItem("mms-3", "", PREVIEW_NOW, SmsStatus.RECEIVED, sender = "Alice", pendingMms = PendingMmsState(3, downloading = true)),
        ),
    )
}

@PreviewsDayNight
@Composable
internal fun ThreadViewPreview(@PreviewParameter(ThreadPreviewParam::class) thread: Pair<SmsThreadInfo, List<SmsItem>>) = ElementPreview {
    ThreadView(info = thread.first, messages = thread.second, text = "", onTextChange = {}, onSend = {}, onRetryMms = {}, onBack = {})
}
