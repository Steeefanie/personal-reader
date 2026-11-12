package top.steeefanie.leafreader.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.readerSettings by preferencesDataStore("reader-settings")
data class ReadingSettings(val fontSize: Double = 22.0, val lineHeight: Double = 1.8, val margins: Double = 1.0, val font: String = "system-default", val showPageNumbers: Boolean = true, val showStatusBar: Boolean = true, val fontWeight: Double = 1.0, val paragraphSpacing: Double = .5, val touchTurnPage: Boolean = true)
class Settings(context: Context) {
    private val store = context.readerSettings
    private val size = doublePreferencesKey("fontSizePoints")
    private val line = doublePreferencesKey("lineHeight")
    private val margin = doublePreferencesKey("margins")
    private val family = stringPreferencesKey("fontFamily")
    private val weight = doublePreferencesKey("fontWeight")
    private val paragraph = doublePreferencesKey("paragraphSpacingV2")
    private val pageNumbers = booleanPreferencesKey("showPageNumbers")
    private val statusBar = booleanPreferencesKey("showStatusBar")
    private val touchTurn = booleanPreferencesKey("touchTurnPage")
    suspend fun read(): ReadingSettings = store.data.first().let {
        val savedFont = it[family]
        ReadingSettings(
            TypographyOptions.nearest(TypographyOptions.sizes,it[size] ?: 22.0,22.0),
            TypographyOptions.nearest(TypographyOptions.lines,it[line] ?: 1.8,1.8),
            TypographyOptions.nearest(TypographyOptions.margins,it[margin] ?: 1.0,1.0),
            savedFont?.takeIf { it.length<=200 } ?: "system-default",
            it[pageNumbers] ?: true,it[statusBar] ?: true,
            (it[weight] ?: 1.0).takeIf { value -> value.isFinite() && value in .0025..2.5 } ?: 1.0,
            TypographyOptions.nearest(TypographyOptions.paragraphs,it[paragraph] ?: .5,.5),
            it[touchTurn] ?: true)
    }
    suspend fun save(value: ReadingSettings) { store.edit {
        it[size] = value.fontSize; it[line] = value.lineHeight; it[margin] = value.margins; it[family] = value.font; it[pageNumbers] = value.showPageNumbers; it[statusBar] = value.showStatusBar; it[weight] = value.fontWeight; it[paragraph] = value.paragraphSpacing; it[touchTurn] = value.touchTurnPage
    } }
}
