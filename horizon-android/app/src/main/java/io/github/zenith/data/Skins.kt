package io.github.zenith.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.zenith.util.Http
import java.io.File
import java.io.IOException

/**
 * Skins: escolher um PNG da galeria (64x64, ou 64x32 do formato antigo), guardar
 * por conta e entregar ao Horizon por HORIZON_OFFLINE_SKIN / _MODEL (o mesmo
 * caminho que o launcher do Horizon usa para nomes offline).
 */
object Skins {
    fun importSkin(context: Context, account: Account, uri: Uri, slim: Boolean): Account {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("não deu para ler a imagem")
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("não é uma imagem")
        if (bmp.width != 64 || (bmp.height != 64 && bmp.height != 32)) {
            throw IOException("A skin precisa ter 64x64 (ou 64x32). Esta tem ${bmp.width}x${bmp.height}.")
        }
        Paths.skinsDir.mkdirs()
        val file = File(Paths.skinsDir, "${account.id}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val updated = account.copy(localSkin = file.path, skinModel = if (slim) "slim" else "classic")
        AccountStore.save(updated)
        return updated
    }

    fun importCape(context: Context, account: Account, uri: Uri): Account {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("não deu para ler a imagem")
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("não é uma imagem")
        if (bmp.width < 22 || bmp.height < 17) throw IOException("Capa pequena demais (o padrão é 64x32)")
        Paths.skinsDir.mkdirs()
        val file = File(Paths.skinsDir, "${account.id}-cape.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val updated = account.copy(localCape = file.path)
        AccountStore.save(updated)
        return updated
    }

    fun clearLocal(account: Account): Account {
        account.skinFile?.delete()
        account.localCape.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        val updated = account.copy(localSkin = "", localCape = "")
        AccountStore.save(updated)
        return updated
    }

    /** A skin a mostrar: a escolhida no celular, senão a da conta (baixada e guardada). */
    fun bitmapFor(account: Account): Bitmap? {
        account.skinFile?.let { return BitmapFactory.decodeFile(it.path) }
        if (account.skinUrl.isBlank()) return null
        val cached = File(Paths.cache, "skins/${AccountStore.sha1(account.skinUrl)}.png")
        if (!cached.isFile) {
            runCatching {
                // Só texturas da Mojang.
                val url = account.skinUrl.replace("http://", "https://")
                if (!url.startsWith("https://textures.minecraft.net/")) return null
                Http.download(url, cached)
            }.onFailure { return null }
        }
        return BitmapFactory.decodeFile(cached.path)
    }

    /** As variáveis de ambiente que o jogo lê para a skin de um nome offline. */
    fun gameEnv(account: Account?): Map<String, String> {
        if (account == null) return emptyMap()
        val env = mutableMapOf<String, String>()
        account.skinFile?.let {
            env["HORIZON_OFFLINE_SKIN"] = it.path
            env["HORIZON_OFFLINE_SKIN_MODEL"] = account.skinModel
        }
        account.localCape.takeIf { it.isNotBlank() && File(it).isFile }?.let { env["HORIZON_OFFLINE_CAPE"] = it }
        return env
    }
}
