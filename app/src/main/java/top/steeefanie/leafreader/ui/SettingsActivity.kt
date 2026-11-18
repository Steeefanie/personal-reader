package top.steeefanie.leafreader.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import top.steeefanie.leafreader.ui.PaperPanel as AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.drive.DriveConnectionActivity
import top.steeefanie.leafreader.storage.*

class SettingsActivity:PaperActivity(){
    private val app get()=application as ReaderApplication
    private val updater by lazy { AppUpdate(applicationContext) }
    private lateinit var updateRow: View
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstallUpdate()) openInstaller() else {
            updater.apk.delete()
            message("请允许图书安装未知应用后重试")
        }
    }
    private val installUpdate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        updater.apk.delete()
        if (result.resultCode == Activity.RESULT_FIRST_USER) message("系统安装失败")
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=column();root.addView(pageHeader("设置"))
        val body=column()
        body.addView(caption("阅读"))
        body.addView(settingRow(Glyph.TYPE,"默认阅读排版","字号、行距、段距、页边距和字体"){lifecycleScope.launch{val current=app.settings.read();showTypography(current){value->lifecycleScope.launch{app.settings.save(value)}}}})
        fun displaySwitch(title:String,initial:(ReadingSettings)->Boolean,update:(ReadingSettings,Boolean)->ReadingSettings){
            val control=androidx.appcompat.widget.SwitchCompat(this).apply{text=title;textSize=PaperText.BODY;minHeight=dp(56);pad(20,8)}
            body.addView(control,LinearLayout.LayoutParams(-1,-2))
            lifecycleScope.launch{
                val current=app.settings.read();control.isChecked=initial(current)
                control.setOnCheckedChangeListener{_,checked->lifecycleScope.launch{
                    val value=app.settings.read();app.settings.save(update(value,checked))
                }}
            }
        }
        displaySwitch("触屏翻页",{it.touchTurnPage},{s,c->s.copy(touchTurnPage=c)})
        displaySwitch("阅读时显示页码",{it.showPageNumbers},{s,c->s.copy(showPageNumbers=c)})
        displaySwitch("阅读时显示系统状态栏",{it.showStatusBar},{s,c->s.copy(showStatusBar=c)})
        body.addView(rule());body.addView(caption("云盘"))
        body.addView(settingRow(Glyph.CLOUD,"连接与账号","管理设备、同步状态和账号空间"){startActivity(Intent(this,DriveConnectionActivity::class.java))})
        body.addView(rule());body.addView(caption("关于"))
        val version=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
        val repoUrl="https://github.com/${AppUpdate.REPOSITORY}"
        body.addView(settingRow(Glyph.BOOK,"图书  $version","开源仓库 · github.com/${AppUpdate.REPOSITORY}"){
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,repoUrl.toUri()))}
                .onFailure{message(repoUrl)}
        })
        updateRow = settingRow(Glyph.SYNC, "检查更新") { checkUpdate(version) }
        body.addView(updateRow)
        body.addView(gap(24))
        val scroll=ScrollView(this).apply{addView(body)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(navigation("设置"){scroll});installRoot(root)
    }

    private fun checkUpdate(version: String) = runUpdate("正在检查更新…") {
        val release = withContext(Dispatchers.IO) { updater.latest(version) }
        lifecycle.withResumed {
            if (release == null) {
                message("已是最新版本（$version）")
            } else {
                AlertDialog.Builder(this).setTitle("发现新版本 ${release.version}")
                    .setMessage("下载完成后，将打开 Android 安装确认页面。")
                    .setPositiveButton("下载并安装") { _, _ ->
                        runUpdate("正在下载更新…") {
                            withContext(Dispatchers.IO) { updater.download(release) }
                            lifecycle.withResumed {
                                if (canInstallUpdate()) {
                                    openInstaller()
                                } else {
                                    installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                                }
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun runUpdate(status: String, action: suspend () -> Unit) {
        if (!updateRow.isEnabled) return
        updateRow.isEnabled = false
        message(status)
        lifecycleScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                updater.apk.delete()
                val detail = if (failure is IllegalStateException || failure is IllegalArgumentException) failure.message else null
                message(detail ?: "更新失败，请检查网络或系统安装设置后重试")
            } finally {
                updateRow.isEnabled = true
            }
        }
    }

    private fun canInstallUpdate(): Boolean = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun openInstaller() {
        val apk = updater.apk
        if (!apk.isFile) { message("更新包已被清除，请重新下载"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            installUpdate.launch(Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (_: ActivityNotFoundException) {
            apk.delete()
            message("此设备未提供系统安装程序")
        } catch (_: SecurityException) {
            apk.delete()
            message("系统阻止了安装，请检查图书的安装权限")
        }
    }
}

fun AppCompatActivity.showTypography(current:ReadingSettings,save:(ReadingSettings)->Unit):AlertDialog {
    val app = application as ReaderApplication
    var selected = app.fonts.resolve(current.font)
    var initial = current.copy(font = selected.id, fontWeight = selected.nearest(current.fontWeight * 400) / 400.0)
    var value = initial
    val content = column().apply { pad(20,8) }
    val controls = mutableListOf<Pair<DiscreteSettingBar,(ReadingSettings)->Double>>()
    val weightButtons = mutableListOf<View>()
    val stepButtons = mutableListOf<View>()
    fun stepper(title:String,values:List<Double>,getter:(ReadingSettings)->Double,left:String,right:String,
                format:(Double)->String,onChange:(Double)->Unit):DiscreteSettingBar {
        val bar = DiscreteSettingBar(this,title,values,getter(initial),left,right,format) { onChange(it);save(value) }
        controls += bar to getter
        content.addView(row().apply {
            fun stepButton(glyph:Glyph,description:String,delta:Int) = iconButton(glyph,description) { bar.step(delta) }.apply {
                background = android.graphics.drawable.InsetDrawable(paperBackground(android.graphics.Color.WHITE,Paper.INK,24),dp(6))
                setPadding(dp(15),dp(15),dp(15),dp(15))
                minimumWidth=dp(48);minimumHeight=dp(48)
                layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))
                stepButtons+=this
                if(title=="字重")weightButtons+=this
            }
            addView(stepButton(Glyph.MINUS,"减小$title",-1))
            addView(bar,LinearLayout.LayoutParams(0,dp(52),1f).apply { leftMargin=dp(6);rightMargin=dp(6) })
            addView(stepButton(Glyph.PLUS,"增大$title",1))
        },LinearLayout.LayoutParams(-1,dp(56)))
        return bar
    }
    stepper("字号",TypographyOptions.sizes,{it.fontSize},"小","大",{it.toInt().toString()}) { value=value.copy(fontSize=it) }
    val weight = stepper("字重",selected.weights.map { it/400.0 },{it.fontWeight},"细","粗",
        {if(selected.generic)"默认" else (it*400).toInt().toString()}) { value=value.copy(fontWeight=it) }
    stepper("行距",TypographyOptions.lines,{it.lineHeight},"紧","松",{"行距"}) { value=value.copy(lineHeight=it) }
    stepper("段距",TypographyOptions.paragraphs,{it.paragraphSpacing},"紧","松",{"段距"}) { value=value.copy(paragraphSpacing=it) }
    stepper("边距",TypographyOptions.margins,{it.margins},"小","大",{"边距"}) { value=value.copy(margins=it) }
    val currentFont = label("正在读取本机字体…",PaperText.BODY).strong().apply { pad(4,8) }
    val detail = label("",PaperText.DETAIL).apply { pad(4,4);setTextColor(Paper.secondary) }
    fun updateWeight() {
        weight.setOptions(selected.weights.map { it/400.0 },value.fontWeight)
        weightButtons.forEach { it.isEnabled=selected.weights.size>1 }
        currentFont.text="当前字体：${selected.name}"
        detail.text=when {
            selected.generic -> "使用设备默认字体和字重"
            selected.weights.size==1 -> "此字体提供 ${selected.weights.single()} 一个字重"
            else -> "可用字重：${selected.weights.joinToString("、")}"
        }
    }
    var families = emptyList<top.steeefanie.leafreader.reader.fonts.DeviceFontFamily>()
    val choose = button("选择本机字体") {
        showFontPicker(families,selected.id,value.fontWeight) { family ->
            selected=family
            value=value.copy(font=selected.id,fontWeight=selected.nearest(value.fontWeight*400)/400.0)
            updateWeight();save(value)
        }
    }.apply { isEnabled=false }
    content.addView(currentFont)
    content.addView(detail)
    content.addView(choose,LinearLayout.LayoutParams(-1,-2))
    val undo=button("撤回") {
        value=initial;selected=app.fonts.resolve(initial.font)
        controls.forEach { (bar,getter) -> bar.setValue(getter(initial)) }
        updateWeight();save(initial)
    }.apply { minHeight=dp(36);minimumHeight=dp(36);pad(12,6);background=paperBackground(android.graphics.Color.WHITE,Paper.line) }
    val dialog=AlertDialog.Builder(this).setTitle("字体设置").setHeaderAction(undo)
        .setView(ScrollView(this).apply { addView(content) })
        .apply { if(resources.configuration.fontScale>=1.5f || resources.configuration.screenWidthDp<400)fullScreen() }
        .show()
    controls.forEach { it.first.isEnabled=false }
    stepButtons.forEach { it.isEnabled=false };undo.isEnabled=false
    val load=lifecycleScope.launch {
        families=app.fonts.families()
        if(!dialog.isShowing)return@launch
        selected=app.fonts.resolve(current.font)
        initial=current.copy(font=selected.id,fontWeight=selected.nearest(current.fontWeight*400)/400.0)
        value=value.copy(font=selected.id,fontWeight=selected.nearest(value.fontWeight*400)/400.0)
        controls.forEach { it.first.isEnabled=true }
        stepButtons.forEach { it.isEnabled=true };undo.isEnabled=true
        updateWeight();choose.isEnabled=true
    }
    dialog.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view:View) = Unit
        override fun onViewDetachedFromWindow(view:View) { load.cancel();view.removeOnAttachStateChangeListener(this) }
    })
    return dialog
}
