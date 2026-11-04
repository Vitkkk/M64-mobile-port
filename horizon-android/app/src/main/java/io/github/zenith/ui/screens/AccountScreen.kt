package io.github.zenith.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zenith.DevFlags
import io.github.zenith.auth.MicrosoftAuth
import io.github.zenith.data.Account
import io.github.zenith.data.AccountStore
import io.github.zenith.data.Skins
import io.github.zenith.ui.LauncherViewModel
import io.github.zenith.ui.components.Chip
import io.github.zenith.ui.components.Section
import io.github.zenith.ui.components.SkinHead
import io.github.zenith.ui.components.SkinPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    var signingIn by remember { mutableStateOf(false) }
    var addingOffline by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Account?>(null) }
    var creatingTest by remember { mutableStateOf(false) }
    val account = vm.account
    var skin by remember(account) { mutableStateOf<Bitmap?>(null) }
    var back by remember { mutableStateOf(false) }
    LaunchedEffect(account) { skin = account?.let { withContext(Dispatchers.IO) { Skins.bitmapFor(it) } } }
    var slimChoice by remember(account) { mutableStateOf(account?.skinModel == "slim") }
    val skinPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val a = account ?: return@rememberLauncherForActivityResult
        if (uri != null) vm.work("Importando a skin…") {
            val updated = Skins.importSkin(context, a, uri, slimChoice)
            vm.toast("Skin aplicada a ${updated.name}" + if (a.isMicrosoft) " (só neste celular; envie para a conta para todos verem)" else "")
        }
    }
    val capePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val a = account ?: return@rememberLauncherForActivityResult
        if (uri != null) vm.work("Importando a capa…") { Skins.importCape(context, a, uri) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Conta e skin", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        Section("Contas", subtitle = "Microsoft para jogar online; nomes offline jogam atrás de uma conta dona do jogo.") {
            if (vm.accounts.isEmpty()) Text("Nenhuma conta ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.accounts.forEach { a ->
                val sel = a.id == account?.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .let { if (sel) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else it }
                        .clickable { vm.selectAccount(a) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var head by remember(a) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(a) { head = withContext(Dispatchers.IO) { Skins.bitmapFor(a) } }
                    SkinHead(head, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (a.isMicrosoft) "Microsoft" else "Offline")
                            if (!a.isMicrosoft && vm.settings.ownerGateBypass) Chip("Teste", MaterialTheme.colorScheme.tertiary)
                            if (a.isMicrosoft && AccountStore.secrets(a.id)?.owns == true) Chip("Tem o Java Edition")
                        }
                    }
                    IconButton(onClick = { removing = a }) { Icon(Icons.Filled.Delete, "Remover") }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { signingIn = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Window, null); Spacer(Modifier.width(6.dp)); Text("Entrar com Microsoft") }
            OutlinedButton(onClick = { addingOffline = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Adicionar nome offline") }
            if (vm.settings.msaClientId.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Este build não tem Client ID do Azure. Informe o seu em Ajustes → Conta Microsoft.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (DevFlags.TEST_ACCOUNT) {
            Section("Modo de teste", subtitle = "Temporário: será removido depois dos testes.") {
                Text(
                    "Cria uma conta offline e desliga a verificação de posse do jogo, para testar sem conta Microsoft. " +
                        "Os recursos do jogo continuam vindo da sua cópia.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { creatingTest = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Science, null); Spacer(Modifier.width(6.dp)); Text("Criar conta de teste (offline)")
                }
            }
        }

        if (account != null) {
            Section("Skin de ${account.name}", subtitle = if (account.skinFile != null) "Skin escolhida neste celular" else if (account.skinUrl.isNotBlank()) "Skin da conta Microsoft" else "Skin padrão do jogo") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { back = !back }) {
                        SkinPreview(skin, account.skinModel == "slim", back = back, height = 220.dp)
                        Text(if (back) "costas · toque para virar" else "frente · toque para virar", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Modelo dos braços", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(!slimChoice, { slimChoice = false }, label = { Text("Clássico") })
                            FilterChip(slimChoice, { slimChoice = true }, label = { Text("Fino") })
                        }
                        Button(onClick = { skinPicker.launch("image/png") }) { Text("Escolher skin (PNG 64x64)") }
                        OutlinedButton(onClick = { capePicker.launch("image/png") }) { Text("Escolher capa") }
                        if (account.skinFile != null || account.localCape.isNotBlank()) {
                            TextButton(onClick = { vm.work("Removendo…") { Skins.clearLocal(account) } }) { Text("Voltar à skin padrão/da conta") }
                        }
                        if (account.isMicrosoft && account.skinFile != null) {
                            OutlinedButton(onClick = {
                                vm.work("Enviando a skin para a conta…") {
                                    MicrosoftAuth(vm.settings.msaClientId).uploadSkin(account, account.skinFile!!, account.skinModel == "slim")
                                    vm.toast("Skin enviada: aparece em qualquer servidor e launcher")
                                }
                            }) { Text("Enviar para a conta Microsoft") }
                        }
                    }
                }
                if (!account.isMicrosoft) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Nomes offline: a skin aparece para você e em mundos/LAN. Em servidores, cada um vê a skin que o servidor manda.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }

    if (signingIn) MicrosoftSignInDialog(vm) { signingIn = false }
    if (addingOffline) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingOffline = false },
            title = { Text("Nome offline") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(16) }, label = { Text("Nome (3 a 16: letras, números, _)") }, singleLine = true, isError = name.isNotEmpty() && !Account.validName(name))
                    if (!vm.settings.ownerGateBypass && !AccountStore.anyOwner()) {
                        Text(
                            "Para jogar com um nome offline, uma conta Microsoft dona do jogo precisa estar no launcher.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = Account.validName(name), onClick = {
                    addingOffline = false
                    vm.work("Adicionando…") { vm.settings.selectedAccount = AccountStore.addOffline(name).id }
                }) { Text("Adicionar") }
            },
            dismissButton = { TextButton(onClick = { addingOffline = false }) { Text("Cancelar") } },
        )
    }
    if (creatingTest) {
        var name by remember { mutableStateOf("Tester") }
        AlertDialog(
            onDismissRequest = { creatingTest = false },
            title = { Text("Conta de teste") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(16) }, label = { Text("Nome no jogo") }, singleLine = true, isError = !Account.validName(name))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Desliga a verificação de posse do jogo (dá para religar em Ajustes → Conta Microsoft). Só para testes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = Account.validName(name), onClick = {
                    creatingTest = false
                    vm.work("Criando a conta de teste…") {
                        val a = DevFlags.createTestAccount(vm.settings, name)
                        vm.toast("Conta de teste ${a.name} pronta")
                    }
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { creatingTest = false }) { Text("Cancelar") } },
        )
    }
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remover ${a.name}?") },
            text = { Text(if (a.isMicrosoft) "Os tokens desta conta são apagados do celular." else "O nome e a skin escolhida saem do launcher. Os mundos ficam.") },
            confirmButton = { TextButton(onClick = { removing = null; vm.work("Removendo…") { AccountStore.remove(a) } }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }
}

/** Login por código: mostra o código, abre microsoft.com/link e espera. */
@Composable
private fun MicrosoftSignInDialog(vm: LauncherViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<MicrosoftAuth.DeviceCode?>(null) }
    var status by remember { mutableStateOf("Pedindo um código à Microsoft…") }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        job = scope.launch {
            try {
                val auth = MicrosoftAuth(vm.settings.msaClientId)
                val c = auth.requestDeviceCode()
                code = c
                status = "Esperando você entrar no navegador…"
                val (access, refresh) = auth.pollToken(c)
                status = "Conferindo o Xbox Live e o Minecraft…"
                val account = auth.completeSignIn(access, refresh)
                vm.settings.selectedAccount = account.id
                vm.toast("Bem-vindo, ${account.name}!")
                vm.refresh()
                onClose()
            } catch (e: Exception) {
                failed = true
                status = e.message ?: "Falha no login"
            }
        }
    }
    AlertDialog(
        onDismissRequest = { job?.cancel(); onClose() },
        title = { Text("Entrar com Microsoft") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                code?.let { c ->
                    Text("Abra ${c.verificationUri} e digite:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(c.userCode, fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("código", c.userCode))
                        }) { Text("Copiar") }
                        Button(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("código", c.userCode))
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.verificationUri)))
                        }) { Text("Copiar e abrir") }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!failed) { CircularProgressIndicator(Modifier.padding(end = 8.dp).width(18.dp).height(18.dp), strokeWidth = 2.dp) }
                    Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = { TextButton(onClick = { job?.cancel(); onClose() }) { Text(if (failed) "Fechar" else "Cancelar") } },
    )
}
