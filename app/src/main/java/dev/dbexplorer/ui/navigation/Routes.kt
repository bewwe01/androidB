package dev.dbexplorer.ui.navigation

import android.net.Uri
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.TableRef

/** String routes; optional arguments are omitted from the URI when null. */
object Routes {
    const val TAB_CONNECTIONS = "tab/connections"
    const val TAB_EXPLORER = "tab/explorer"
    const val TAB_QUERY = "tab/query"
    const val TAB_HISTORY = "tab/history"

    const val CONNECTION_LIST = "connections"
    const val CONNECTION_EDIT = "connections/edit?id={id}"
    const val EXPLORER_HOME = "explorer"
    const val SCHEMAS = "explorer/{connId}"
    const val OBJECTS = "explorer/{connId}/objects?catalog={catalog}&schema={schema}"
    const val TABLE = "explorer/{connId}/table?catalog={catalog}&schema={schema}&name={name}&type={type}"
    const val QUERY = "query"
    const val HISTORY = "history"

    fun connectionEdit(id: Long?) = "connections/edit?id=${id ?: -1}"

    fun schemas(connId: Long) = "explorer/$connId"

    fun objects(connId: Long, ref: SchemaRef) = "explorer/$connId/objects" + query("catalog" to ref.catalog, "schema" to ref.schema)

    fun table(connId: Long, t: TableRef) =
        "explorer/$connId/table" + query("catalog" to t.catalog, "schema" to t.schema, "name" to t.name, "type" to t.type.name)

    private fun query(vararg params: Pair<String, String?>): String {
        val present = params.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString("&", prefix = "?") { (k, v) -> "$k=${Uri.encode(v)}" }
    }
}
