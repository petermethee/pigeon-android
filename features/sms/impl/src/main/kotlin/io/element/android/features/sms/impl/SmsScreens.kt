/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
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
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.asAndroidBitmap
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
import io.element.android.libraries.designsystem.theme.aliasScreenTitle
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
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

    // Pigeon doit être l'app SMS par défaut pour lire et envoyer les SMS : sinon, un bandeau propose de le devenir.
    // Vérifié à chaque retour sur l'écran (le réglage a pu changer entre-temps).
    var isDefaultApp by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumed) {
        if (resumed) {
            val nowDefault = isDefaultSmsApp(context)
            if (nowDefault && !isDefaultApp) refresh++ // devenue app par défaut : recharge
            isDefaultApp = nowDefault
        }
    }
    val defaultAppRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultApp = isDefaultSmsApp(context)
        refresh++
    }
    val notDefaultApp = stringResource(R.string.screen_sms_error_not_default_app)

    // Augmente à chaque changement dans la base SMS/MMS (nouveau message…), à l'accord des contacts
    // ou quand Pigeon devient l'app par défaut : déclenche un rechargement.
    val version = rememberSmsChanges(context) + contactsGranted + refresh

    // Texte proposé par une autre app sans destinataire : on demande le numéro avant d'ouvrir la conversation.
    var draftWithoutRecipient by rememberSaveable { mutableStateOf<String?>(null) }

    // Ouvre une conversation par son fil (notification) ou un numéro ("Envoyer un SMS" depuis une autre app, bouton +).
    val scope = rememberCoroutineScope()
    fun open(threadId: Long?, address: String?, draft: String? = null) {
        if (threadId == null && address == null) {
            openThread = null
            draftWithoutRecipient = draft
            return
        }
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                try {
                    val info = when {
                        threadId != null -> SmsRepository.threadInfo(context, threadId)
                        address != null -> SmsRepository.threadInfo(context, address)
                        else -> null
                    }
                    info?.let { OpenThread(it, draft) }
                } catch (e: Exception) {
                    // Sans le rôle d'app SMS par défaut, Android refuse l'accès aux conversations.
                    Timber.w(e, "Ouverture de la conversation impossible")
                    null
                }
            }
            if (opened != null) {
                openThread = opened
            } else {
                Toast.makeText(context, notDefaultApp, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(target) {
        target?.let { open(it.threadId, it.address, it.draft) }
    }

    // Derniers contenus chargés, gardés pendant la navigation : la liste et une conversation déjà ouverte
    // s'affichent tout de suite, puis sont rafraîchies en arrière-plan.
    var conversations by remember { mutableStateOf<List<SmsConversation>?>(null) }
    val messagesCache = remember { mutableStateMapOf<Long, List<SmsItem>>() }

    val thread = openThread
    if (thread == null) {
        ConversationListScreen(
            version = version,
            conversations = conversations,
            onLoaded = { conversations = it },
            isDefaultApp = isDefaultApp,
            onMakeDefault = { defaultAppRequest.launch(makeDefaultSmsAppIntent(context)) },
            askRecipient = draftWithoutRecipient != null,
            onOpen = { openThread = OpenThread(SmsThreadInfo(it.threadId, it.addresses, it.displayName)) },
            onNewConversation = { number ->
                open(threadId = null, address = number, draft = draftWithoutRecipient)
                draftWithoutRecipient = null
            },
            onNewConversationDismissed = { draftWithoutRecipient = null },
        )
    } else {
        BackHandler { openThread = null }
        ThreadScreen(
            thread = thread,
            version = version,
            resumed = resumed,
            messages = messagesCache[thread.info.threadId].orEmpty(),
            onLoaded = { messagesCache[thread.info.threadId] = it },
            onBack = { openThread = null },
        )
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
private fun ConversationListScreen(
    version: Int,
    conversations: List<SmsConversation>?,
    onLoaded: (List<SmsConversation>) -> Unit,
    isDefaultApp: Boolean,
    onMakeDefault: () -> Unit,
    askRecipient: Boolean,
    onOpen: (SmsConversation) -> Unit,
    onNewConversation: (String) -> Unit,
    onNewConversationDismissed: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(version) {
        onLoaded(withContext(Dispatchers.IO) { SmsRepository.loadConversations(context) })
    }
    ConversationListView(
        conversations = conversations,
        isDefaultApp = isDefaultApp,
        onMakeDefault = onMakeDefault,
        askRecipient = askRecipient,
        onOpen = onOpen,
        onNewConversation = onNewConversation,
        onNewConversationDismissed = onNewConversationDismissed,
    )
}

/**
 * Liste des conversations ; [conversations] vaut null pendant le chargement.
 *
 * @param askRecipient ouvre directement la saisie du numéro (texte proposé par une autre app sans destinataire).
 */
@Composable
private fun ConversationListView(
    conversations: List<SmsConversation>?,
    isDefaultApp: Boolean,
    onMakeDefault: () -> Unit,
    askRecipient: Boolean,
    onOpen: (SmsConversation) -> Unit,
    onNewConversation: (String) -> Unit,
    onNewConversationDismissed: () -> Unit,
) {
    var showNewConversation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(askRecipient) {
        if (askRecipient) showNewConversation = true
    }
    Scaffold(
        topBar = { TopAppBar(titleStr = stringResource(R.string.screen_sms_list_title)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewConversation = true }) {
                Icon(CompoundIcons.Plus(), contentDescription = stringResource(R.string.screen_sms_new_conversation))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isDefaultApp) NotDefaultAppBanner(onMakeDefault)
            when {
                conversations == null -> Unit // chargement
                conversations.isEmpty() -> CenteredText(stringResource(R.string.screen_sms_list_empty))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.threadId }) { conversation ->
                        ConversationRow(conversation, onClick = { onOpen(conversation) })
                        HorizontalDivider()
                    }
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
            onDismissRequest = {
                showNewConversation = false
                onNewConversationDismissed()
            },
            value = null,
            placeholder = stringResource(R.string.screen_sms_new_conversation_number),
            validation = { !it.isNullOrBlank() },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

/** Bandeau "Pigeon n'est pas l'app SMS par défaut", avec un bouton pour le devenir. */
@Composable
private fun NotDefaultAppBanner(onMakeDefault: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCriticalSubtle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.screen_sms_not_default_app),
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = stringResource(R.string.screen_sms_make_default_app), onClick = onMakeDefault, size = ButtonSize.Medium)
    }
}

/** Pigeon est l'app SMS par défaut du téléphone. */
private fun isDefaultSmsApp(context: Context): Boolean = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

/** Demande à Android de faire de Pigeon l'app SMS par défaut. */
private fun makeDefaultSmsAppIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
    } else {
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
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
private fun ThreadScreen(
    thread: OpenThread,
    version: Int,
    resumed: Boolean,
    messages: List<SmsItem>,
    onLoaded: (List<SmsItem>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = thread.info
    var text by rememberSaveable(info.threadId) { mutableStateOf(thread.draft.orEmpty()) }
    var photo by rememberSaveable(info.threadId) { mutableStateOf<Uri?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri
    }

    LaunchedEffect(info.threadId, version) {
        onLoaded(withContext(Dispatchers.IO) { SmsRepository.loadMessages(context, info.threadId, info.isGroup) })
    }
    // Lus seulement s'ils sont vraiment à l'écran (pas quand l'app est en arrière-plan), après l'affichage.
    LaunchedEffect(info.threadId, version, resumed) {
        if (resumed) withContext(Dispatchers.IO) { SmsRepository.markThreadRead(context, info.threadId) }
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
    val photoError = stringResource(R.string.screen_sms_error_photo)
    ThreadView(
        info = info,
        messages = messages,
        text = text,
        photo = photo,
        onTextChange = { text = it },
        onAttachPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemovePhoto = { photo = null },
        onSend = {
            val body = text.trim()
            val image = photo
            if (body.isNotEmpty() || image != null) {
                text = ""
                photo = null
                scope.launch {
                    // Un texte seul à une personne part en SMS ; une photo, ou tout message à un groupe, en MMS.
                    val result = withContext(Dispatchers.IO) {
                        if (image == null && !info.isGroup) {
                            if (SmsSender.send(context, info.addresses.first(), body)) MmsSendResult.STARTED else MmsSendResult.FAILED
                        } else {
                            MmsSender.send(context, info.addresses, body, image)
                        }
                    }
                    when (result) {
                        MmsSendResult.STARTED -> Unit
                        MmsSendResult.PHOTO_UNREADABLE -> {
                            // Rien n'est parti : le texte est remis dans la zone de saisie.
                            if (text.isEmpty()) text = body
                            Toast.makeText(context, photoError, Toast.LENGTH_LONG).show()
                        }
                        MmsSendResult.FAILED -> Toast.makeText(context, notDefaultApp, Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        onRetryMms = { pendingId -> scope.launch(Dispatchers.IO) { MmsDownloader.retry(context, pendingId) } },
        onResendMms = { mmsId ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { MmsSender.resend(context, mmsId) }
                if (result != MmsSendResult.STARTED) Toast.makeText(context, notDefaultApp, Toast.LENGTH_LONG).show()
            }
        },
        onBack = onBack,
    )
}

@Composable
private fun ThreadView(
    info: SmsThreadInfo,
    messages: List<SmsItem>,
    text: String,
    photo: Uri?,
    onTextChange: (String) -> Unit,
    onAttachPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSend: () -> Unit,
    onRetryMms: (Long) -> Unit,
    onResendMms: (Long) -> Unit,
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
            if (info.addresses.isEmpty()) {
                BottomNotice(stringResource(R.string.screen_sms_unknown_recipient))
            } else {
                Composer(text = text, photo = photo, onTextChange = onTextChange, onAttachPhoto = onAttachPhoto, onRemovePhoto = onRemovePhoto, onSend = onSend)
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
                MessageBubble(message, onImageClick = { fullScreenImage = it }, onRetryMms = onRetryMms, onResendMms = onResendMms)
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

/** Zone de saisie, avec la photo choisie au-dessus ; elle remonte au-dessus du clavier. */
@Composable
private fun Composer(
    text: String,
    photo: Uri?,
    onTextChange: (String) -> Unit,
    onAttachPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElementTheme.colors.bgCanvasDefault)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (photo != null) {
            Box(modifier = Modifier.padding(start = 48.dp, bottom = 6.dp)) {
                MmsImage(
                    uri = photo,
                    maxSize = 512,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                IconButton(
                    onClick = onRemovePhoto,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .background(ElementTheme.colors.bgCanvasDefault.copy(alpha = 0.8f), CircleShape),
                ) {
                    Icon(CompoundIcons.Close(), contentDescription = stringResource(R.string.screen_sms_remove_photo))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAttachPhoto) {
                Icon(CompoundIcons.Image(), contentDescription = stringResource(R.string.screen_sms_attach_photo))
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = stringResource(R.string.screen_sms_composer_placeholder),
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend, enabled = text.isNotBlank() || photo != null) {
                Icon(CompoundIcons.Send(), contentDescription = stringResource(CommonStrings.action_send))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsItem, onImageClick: (Uri) -> Unit, onRetryMms: (Long) -> Unit, onResendMms: (Long) -> Unit) {
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
                .then(
                    when {
                        pending != null && !pending.downloading -> Modifier.clickable { onRetryMms(pending.id) }
                        message.failedMmsId != null -> Modifier.clickable { onResendMms(message.failedMmsId) }
                        else -> Modifier
                    }
                )
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
                    SmsStatus.FAILED -> stringResource(if (message.failedMmsId != null) R.string.screen_sms_status_failed_retry else R.string.screen_sms_status_failed)
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
private fun MmsImage(uri: Uri, maxSize: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.FillWidth) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val cacheKey = "$uri@$maxSize"
    val bitmap by produceState<ImageBitmap?>(initialValue = imageCache.get(cacheKey), uri, maxSize) {
        if (!inPreview && value == null) {
            value = withContext(Dispatchers.IO) { decodeImage(context, uri, maxSize) }?.also { imageCache.put(cacheKey, it) }
        }
    }
    val image = bitmap
    if (image == null) {
        Box(modifier = modifier.size(width = 220.dp, height = 160.dp).background(ElementTheme.colors.bgSubtlePrimary))
    } else {
        Image(bitmap = image, contentDescription = stringResource(R.string.screen_sms_photo), contentScale = contentScale, modifier = modifier)
    }
}

/** Photos déjà décodées (32 Mo au plus) : revenir sur une conversation ne les redécode pas. */
private val imageCache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
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
    override val values: Sequence<List<SmsConversation>?> = sequenceOf(
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
    ConversationListView(conversations = conversations, isDefaultApp = conversations != null, onMakeDefault = {}, askRecipient = false, onOpen = {}, onNewConversation = {}, onNewConversationDismissed = {})
}

internal data class ThreadPreviewState(val info: SmsThreadInfo, val messages: List<SmsItem>, val photo: Uri? = null)

internal class ThreadPreviewParam : PreviewParameterProvider<ThreadPreviewState> {
    override val values: Sequence<ThreadPreviewState> = sequenceOf(
        ThreadPreviewState(
            info = SmsThreadInfo(1, listOf("+33611111111"), "Alice"),
            messages = listOf(
                SmsItem("sms-1", "Salut, tu viens ce soir ?", PREVIEW_NOW - 60_000, SmsStatus.RECEIVED),
                SmsItem("sms-2", "Oui, vers 20 h", PREVIEW_NOW - 30_000, SmsStatus.SENT),
                SmsItem("sms-3", "Je prends le pain", PREVIEW_NOW, SmsStatus.SENDING),
                SmsItem("sms-4", "Message non parti", PREVIEW_NOW, SmsStatus.FAILED),
            ),
        ),
        ThreadPreviewState(
            info = SmsThreadInfo(3, listOf("+33611111111", "+33633333333"), "Alice, Carole"),
            messages = listOf(
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
        ),
        // Photo choisie, pas encore envoyée.
        ThreadPreviewState(
            info = SmsThreadInfo(1, listOf("+33611111111"), "Alice"),
            messages = listOf(
                SmsItem("mms-4", "Voilà la photo", PREVIEW_NOW, SmsStatus.SENT, attachments = listOf(MmsAttachment(Uri.EMPTY, "image/jpeg"))),
                SmsItem("mms-5", "Et celle-ci", PREVIEW_NOW, SmsStatus.FAILED, attachments = listOf(MmsAttachment(Uri.EMPTY, "image/jpeg")), failedMmsId = 5),
            ),
            photo = Uri.EMPTY,
        ),
    )
}

@PreviewsDayNight
@Composable
internal fun ThreadViewPreview(@PreviewParameter(ThreadPreviewParam::class) state: ThreadPreviewState) = ElementPreview {
    ThreadView(
        info = state.info,
        messages = state.messages,
        text = "",
        photo = state.photo,
        onTextChange = {},
        onAttachPhoto = {},
        onRemovePhoto = {},
        onSend = {},
        onRetryMms = {},
        onResendMms = {},
        onBack = {},
    )
}
