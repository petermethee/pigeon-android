/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.element.android.compound.theme.ElementTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Conversation ouverte : son fil et son correspondant. */
private data class OpenThread(val threadId: Long, val address: String, val title: String)

@Composable
internal fun SmsApp(initialAddress: String?) {
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

    // Ouverture depuis une notification : directement sur la conversation.
    LaunchedEffect(initialAddress) {
        if (initialAddress != null) {
            openThread = withContext(Dispatchers.IO) {
                OpenThread(
                    threadId = SmsRepository.threadIdFor(context, initialAddress),
                    address = initialAddress,
                    title = SmsRepository.displayName(context, initialAddress),
                )
            }
        }
    }

    val thread = openThread
    if (thread == null) {
        ConversationListScreen(version = version, onOpen = { openThread = OpenThread(it.threadId, it.address, it.displayName) })
    } else {
        BackHandler { openThread = null }
        ThreadScreen(thread = thread, version = version, onBack = { openThread = null })
    }
}

/** Compteur qui augmente à chaque modification de la base SMS. */
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
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return version
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(version: Int, onOpen: (SmsConversation) -> Unit) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<SmsConversation>?>(null) }
    LaunchedEffect(version) {
        conversations = withContext(Dispatchers.IO) { SmsRepository.loadConversations(context) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SMS", style = ElementTheme.typography.fontHeadingSmMedium) }) },
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
                text = conversation.snippet,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadScreen(thread: OpenThread, version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var messages by remember(thread.threadId) { mutableStateOf<List<SmsItem>>(emptyList()) }
    LaunchedEffect(thread.threadId, version) {
        messages = withContext(Dispatchers.IO) {
            SmsRepository.markThreadRead(context, thread.threadId)
            SmsRepository.loadMessages(context, thread.threadId)
        }
        // La conversation est lue : on retire sa notification.
        NotificationManagerCompat.from(context).cancel(thread.address.hashCode())
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(thread.title, style = ElementTheme.typography.fontHeadingSmMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            // L'envoi arrive à l'étape 4.
            Text(
                text = "Envoi de SMS depuis Pigeon : bientôt",
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .padding(16.dp),
            )
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
            items(messages, key = { it.id }) { MessageBubble(it) }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsItem) {
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
            Text(text = message.body, style = ElementTheme.typography.fontBodyLgRegular, color = textColor)
            Text(
                text = formatDate(message.date),
                style = ElementTheme.typography.fontBodySmRegular,
                color = if (incoming) ElementTheme.colors.textSecondary else ElementTheme.colors.textOnSolidPrimary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
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
