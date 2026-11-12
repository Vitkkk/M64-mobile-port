package io.github.zenith.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream

/**
 * Uma pasta escolhida pelo seletor de pastas do Android (ACTION_OPEN_DOCUMENT_TREE) como
 * [LocalImport.Tree]. Cada pasta é listada uma vez só (o seletor responde por consulta, e uma
 * `.minecraft` tem milhares de arquivos). Não precisa de permissão de armazenamento: o acesso
 * é o que a pessoa deu ao escolher a pasta.
 */
class SafTree private constructor(private val context: Context, private val doc: DocumentFile) : LocalImport.Tree {
    private val kids: Map<String, SafTree> by lazy {
        doc.listFiles().mapNotNull { d -> d.name?.let { it to SafTree(context, d) } }.toMap()
    }

    override val name: String get() = doc.name ?: "?"
    override fun child(name: String): LocalImport.Tree? = kids[name]
    override fun children(): List<LocalImport.Tree> = kids.values.toList()
    override val isDirectory: Boolean get() = doc.isDirectory
    override val length: Long get() = doc.length()
    override fun open(): InputStream = context.contentResolver.openInputStream(doc.uri) ?: throw IOException("Não deu para abrir $name")

    companion object {
        fun from(context: Context, tree: Uri): SafTree =
            SafTree(context, DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Pasta inválida"))

        /** Nome e tamanho de um arquivo escolhido no seletor (ACTION_OPEN_DOCUMENT). */
        fun describe(context: Context, uri: Uri): Pair<String, Long> {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: uri.lastPathSegment ?: "arquivo"
                    val size = if (c.isNull(1)) -1L else c.getLong(1)
                    return name to size
                }
            }
            return (uri.lastPathSegment ?: "arquivo") to -1L
        }
    }
}
