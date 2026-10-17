/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.bridges.impl

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.qrcode.QrCodeImage
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
internal fun BridgesApp(accounts: ConnectedAccounts, onClose: () -> Unit) {
    LaunchedEffect(Unit) { accounts.refresh() }
    val login = accounts.login
    if (login == null) {
        AccountsView(
            accounts = BridgeNetwork.entries.associateWith { accounts.accounts[it] ?: NetworkAccounts.Loading },
            onAddAccount = accounts::startLogin,
            onLogout = accounts::logout,
            onRetry = accounts::refresh,
            onBack = onClose,
        )
    } else {
        val finished = login is LoginState.Done || login is LoginState.Failed
        BackHandler { if (finished) accounts.closeLogin() else accounts.cancelLogin() }
        LoginView(
            state = login,
            onSubmit = accounts::submit,
            onCancel = accounts::cancelLogin,
            onClose = accounts::closeLogin,
        )
    }
}

// ── Liste des comptes ───────────────────────────────────────────────────────────

@Composable
private fun AccountsView(
    accounts: Map<BridgeNetwork, NetworkAccounts>,
    onAddAccount: (BridgeNetwork) -> Unit,
    onLogout: (BridgeNetwork, String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf<Pair<BridgeNetwork, WhoamiLogin>?>(null) }
    Scaffold(
        topBar = { TopAppBar(titleStr = stringResource(R.string.screen_bridges_title), navigationIcon = { BackButton(onClick = onBack) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            for ((network, state) in accounts) {
                NetworkSection(
                    network = network,
                    state = state,
                    onAddAccount = { onAddAccount(network) },
                    onLogout = { confirmLogout = network to it },
                    onRetry = onRetry,
                )
                HorizontalDivider()
            }
        }
    }
    confirmLogout?.let { (network, login) ->
        ConfirmationDialog(
            title = stringResource(R.string.screen_bridges_disconnect_title, login.displayName()),
            content = stringResource(R.string.screen_bridges_disconnect_text),
            submitText = stringResource(R.string.screen_bridges_disconnect),
            destructiveSubmit = true,
            onSubmitClick = {
                confirmLogout = null
                onLogout(network, login.id)
            },
            onDismiss = { confirmLogout = null },
        )
    }
}

@Composable
private fun NetworkSection(
    network: BridgeNetwork,
    state: NetworkAccounts,
    onAddAccount: () -> Unit,
    onLogout: (WhoamiLogin) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(network.label), style = ElementTheme.typography.fontHeadingSmMedium, color = ElementTheme.colors.textPrimary)
        when (state) {
            NetworkAccounts.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            is NetworkAccounts.Failed -> {
                SecondaryText(stringResource(R.string.screen_bridges_unreachable, state.error.describe()), critical = true)
                TextButton(text = stringResource(R.string.screen_bridges_retry), onClick = onRetry, size = ButtonSize.Medium)
            }
            is NetworkAccounts.Loaded -> {
                if (state.logins.isEmpty()) SecondaryText(stringResource(R.string.screen_bridges_no_account))
                for (login in state.logins) {
                    LoginRow(login, onLogout = { onLogout(login) })
                }
                OutlinedButton(text = stringResource(R.string.screen_bridges_add_account), onClick = onAddAccount, size = ButtonSize.Medium)
            }
        }
    }
}

@Composable
private fun LoginRow(login: WhoamiLogin, onLogout: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = login.displayName(), style = ElementTheme.typography.fontBodyLgRegular, color = ElementTheme.colors.textPrimary)
            val (status, problem) = login.status()
            SecondaryText(status, critical = problem)
        }
        TextButton(text = stringResource(R.string.screen_bridges_disconnect), onClick = onLogout, size = ButtonSize.Medium)
    }
}

/** Numéro, nom ou identifiant du compte connecté. */
private fun WhoamiLogin.displayName(): String =
    listOfNotNull(profile?.phone, profile?.name, profile?.username, name).firstOrNull { it.isNotBlank() } ?: id

/** État de la connexion du compte, et s'il s'agit d'un problème. */
@Composable
private fun WhoamiLogin.status(): Pair<String, Boolean> {
    val event = state?.stateEvent.orEmpty()
    return when (event) {
        "CONNECTED", "BACKFILLING" -> stringResource(R.string.screen_bridges_state_connected) to false
        "CONNECTING", "" -> stringResource(R.string.screen_bridges_state_connecting) to false
        "LOGGED_OUT" -> stringResource(R.string.screen_bridges_state_disconnected) to true
        else -> stringResource(R.string.screen_bridges_state_error, state?.message ?: state?.error ?: event) to true
    }
}

@Composable
private fun ProvisioningException.describe(): String =
    if (status == 401) stringResource(R.string.screen_bridges_not_logged_in) else if (status == 0) message else "HTTP $status : $message"

@Composable
private fun SecondaryText(text: String, modifier: Modifier = Modifier, critical: Boolean = false) {
    Text(
        text = text,
        style = ElementTheme.typography.fontBodyMdRegular,
        color = if (critical) ElementTheme.colors.textCriticalPrimary else ElementTheme.colors.textSecondary,
        modifier = modifier,
    )
}

// ── Connexion d'un compte ───────────────────────────────────────────────────────

@Composable
private fun LoginView(
    state: LoginState,
    onSubmit: (LoginStep, Map<String, String>) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val networkName = stringResource(state.network.label)
    val finished = state is LoginState.Done || state is LoginState.Failed
    Scaffold(
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_bridges_login_title, networkName),
                navigationIcon = { BackButton(onClick = if (finished) onClose else onCancel) },
            )
        },
    ) { padding ->
        val step = (state as? LoginState.Step)?.step
        if (step?.type == LoginStep.TYPE_COOKIES && step.cookies != null) {
            // Page de connexion : toute la place pour elle.
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                SecondaryText(stringResource(R.string.screen_bridges_messenger_web), modifier = Modifier.padding(16.dp))
                CookieLogin(params = step.cookies, onCookies = { onSubmit(step, it) })
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is LoginState.Busy -> CircularProgressIndicator()
                is LoginState.Done -> {
                    BodyText(stringResource(R.string.screen_bridges_done, networkName))
                    Button(text = stringResource(CommonStrings.action_done), onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
                is LoginState.Failed -> {
                    BodyText(stringResource(R.string.screen_bridges_failed, state.message))
                    Button(text = stringResource(CommonStrings.action_ok), onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
                is LoginState.Step -> when (state.step.type) {
                    LoginStep.TYPE_USER_INPUT -> UserInputStep(state.network, state.step, onSubmit = { onSubmit(state.step, it) })
                    LoginStep.TYPE_DISPLAY_AND_WAIT -> DisplayStep(state.network, state.step)
                    // Étape que Pigeon ne sait pas faire (clé d'accès…) : on affiche les explications du bridge.
                    else -> BodyText(state.step.instructions.ifBlank { state.step.type })
                }
            }
            if (!finished) {
                TextButton(text = stringResource(CommonStrings.action_cancel), onClick = onCancel)
            }
        }
    }
}

/** Saisie demandée par le bridge (numéro de téléphone, code de vérification…). */
@Composable
private fun UserInputStep(network: BridgeNetwork, step: LoginStep, onSubmit: (Map<String, String>) -> Unit) {
    val fields = step.userInput?.fields.orEmpty()
    var values by remember(step.stepId, step.txnId) { mutableStateOf(fields.associate { it.id to it.defaultValue }) }
    BodyText(
        if (network == BridgeNetwork.WHATSAPP && fields.any { it.type == "phone_number" }) {
            stringResource(R.string.screen_bridges_whatsapp_phone)
        } else {
            step.instructions
        }
    )
    for (field in fields) {
        TextField(
            value = values[field.id].orEmpty(),
            onValueChange = { values = values + (field.id to it) },
            label = field.name.ifBlank { null },
            supportingText = field.description.ifBlank { null },
            placeholder = if (field.type == "phone_number") "+33 6 12 34 56 78" else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType()),
            visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        text = stringResource(R.string.screen_bridges_continue),
        onClick = { onSubmit(values.mapValues { (_, value) -> value.trim() }) },
        enabled = fields.all { values[it.id].orEmpty().isNotBlank() },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun InputField.keyboardType(): KeyboardType = when (type) {
    "phone_number" -> KeyboardType.Phone
    "email" -> KeyboardType.Email
    "password" -> KeyboardType.Password
    "2fa_code" -> KeyboardType.Number
    else -> KeyboardType.Text
}

/** Code à saisir sur le réseau (WhatsApp), QR code ou émojis, pendant que le bridge attend. */
@Composable
private fun DisplayStep(network: BridgeNetwork, step: LoginStep) {
    val context = LocalContext.current
    val display = step.displayAndWait
    when (display?.type) {
        "code" -> {
            BodyText(if (network == BridgeNetwork.WHATSAPP) stringResource(R.string.screen_bridges_whatsapp_code) else step.instructions)
            Text(
                text = display.data,
                style = ElementTheme.typography.fontHeadingLgBold.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                color = ElementTheme.colors.textPrimary,
            )
            val copied = stringResource(R.string.screen_bridges_code_copied)
            OutlinedButton(
                text = stringResource(R.string.screen_bridges_copy_code),
                onClick = {
                    copyToClipboard(context, display.data)
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (network == BridgeNetwork.WHATSAPP) {
                val launch = remember { whatsAppLaunchIntent(context) }
                if (launch != null) {
                    Button(text = stringResource(R.string.screen_bridges_open_whatsapp), onClick = { context.startActivity(launch) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        "qr" -> {
            BodyText(step.instructions)
            if (!LocalInspectionMode.current) QrCodeImage(data = display.data, modifier = Modifier.size(240.dp))
        }
        "emoji" -> {
            BodyText(step.instructions)
            Text(text = display.data, style = ElementTheme.typography.fontHeadingLgBold)
        }
        else -> BodyText(step.instructions)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        SecondaryText(stringResource(R.string.screen_bridges_waiting))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

/** WhatsApp, ou WhatsApp Business, s'il est installé. */
private fun whatsAppLaunchIntent(context: Context) =
    listOf("com.whatsapp", "com.whatsapp.w4b").firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

/**
 * Page de connexion du réseau (messenger.com). Une fois l'utilisateur connecté, les cookies de session demandés
 * par le bridge sont lus et lui sont transmis : c'est ainsi que le bridge se connecte au compte.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CookieLogin(params: CookiesParams, onCookies: (Map<String, String>) -> Unit) {
    if (LocalInspectionMode.current) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val currentOnCookies by rememberUpdatedState(onCookies)
    val pattern = remember(params) { params.waitForUrlPattern?.let { runCatching { Regex(it) }.getOrNull() } }
    var sent by remember(params) { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                params.userAgent?.let { settings.userAgentString = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = check(url)

                    override fun onPageFinished(view: WebView, url: String?) = check(url)

                    private fun check(url: String?) {
                        if (sent || url == null) return
                        if (pattern != null && !pattern.containsMatchIn(url)) return
                        // Cookies absents : pas encore connecté, on attend la page suivante.
                        val values = readCookies(params) ?: return
                        sent = true
                        currentOnCookies(values)
                    }
                }
                loadUrl(params.url)
            }
        },
    )
}

/** Valeurs des cookies demandés, ou null s'il en manque un obligatoire. */
private fun readCookies(params: CookiesParams): Map<String, String>? {
    val manager = CookieManager.getInstance()
    val defaultDomain = Uri.parse(params.url).host.orEmpty().removePrefix("www.")
    val values = HashMap<String, String>()
    for (field in params.fields) {
        val value = field.sources.filter { it.type == "cookie" }.firstNotNullOfOrNull { source ->
            val domain = source.cookieDomain?.removePrefix(".") ?: defaultDomain
            val header = manager.getCookie("https://www.$domain/") ?: manager.getCookie("https://$domain/")
            header?.split(';')
                ?.map { it.trim().split('=', limit = 2) }
                ?.firstOrNull { it.size == 2 && it[0] == source.name }
                ?.get(1)
                ?.takeIf { it.isNotEmpty() }
        }
        when {
            value != null -> values[field.id] = value
            field.required -> return null
        }
    }
    return values
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = ElementTheme.typography.fontBodyLgRegular, color = ElementTheme.colors.textPrimary, textAlign = TextAlign.Center)
}

// ── Aperçus ─────────────────────────────────────────────────────────────────────

internal class AccountsPreviewParam : PreviewParameterProvider<Map<BridgeNetwork, NetworkAccounts>> {
    override val values: Sequence<Map<BridgeNetwork, NetworkAccounts>> = sequenceOf(
        mapOf(
            BridgeNetwork.WHATSAPP to NetworkAccounts.Loaded(
                listOf(
                    WhoamiLogin("1", "Perso", BridgeState("CONNECTED"), RemoteProfile(phone = "+33611111111")),
                    WhoamiLogin("2", "Pro", BridgeState("BAD_CREDENTIALS", message = "Logged out from phone"), RemoteProfile(phone = "+33622222222")),
                )
            ),
            BridgeNetwork.MESSENGER to NetworkAccounts.Loaded(emptyList()),
        ),
        mapOf(
            BridgeNetwork.WHATSAPP to NetworkAccounts.Loading,
            BridgeNetwork.MESSENGER to NetworkAccounts.Failed(ProvisioningException(404, "Unrecognized endpoint")),
        ),
    )
}

@PreviewsDayNight
@Composable
internal fun AccountsViewPreview(@PreviewParameter(AccountsPreviewParam::class) accounts: Map<BridgeNetwork, NetworkAccounts>) = ElementPreview {
    AccountsView(accounts = accounts, onAddAccount = {}, onLogout = { _, _ -> }, onRetry = {}, onBack = {})
}

internal class LoginPreviewParam : PreviewParameterProvider<LoginState> {
    override val values: Sequence<LoginState> = sequenceOf(
        LoginState.Busy(BridgeNetwork.WHATSAPP),
        LoginState.Step(
            BridgeNetwork.WHATSAPP,
            LoginStep(
                loginId = "l1",
                type = LoginStep.TYPE_USER_INPUT,
                stepId = "fi.mau.whatsapp.login.phone",
                userInput = UserInput(listOf(InputField("phone_number", "phone_number", name = "Phone number"))),
            ),
        ),
        LoginState.Step(
            BridgeNetwork.WHATSAPP,
            LoginStep(loginId = "l1", type = LoginStep.TYPE_DISPLAY_AND_WAIT, stepId = "fi.mau.whatsapp.login.code", displayAndWait = DisplayAndWait("code", "ABCD-EFGH")),
            waiting = true,
        ),
        LoginState.Step(
            BridgeNetwork.MESSENGER,
            LoginStep(loginId = "l2", type = LoginStep.TYPE_COOKIES, stepId = "fi.mau.meta.cookies", cookies = CookiesParams("https://www.messenger.com/")),
        ),
        LoginState.Done(BridgeNetwork.MESSENGER),
        LoginState.Failed(BridgeNetwork.WHATSAPP, "Login process timed out"),
    )
}

@PreviewsDayNight
@Composable
internal fun LoginViewPreview(@PreviewParameter(LoginPreviewParam::class) state: LoginState) = ElementPreview {
    LoginView(state = state, onSubmit = { _, _ -> }, onCancel = {}, onClose = {})
}
