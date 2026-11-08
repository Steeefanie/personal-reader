package top.steeefanie.leafreader.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import top.steeefanie.leafreader.ui.PaperPanel as AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.drive.DriveConnectionActivity
import top.steeefanie.leafreader.storage.*

class SettingsActivity:PaperActivity(){
    private val app get()=application as ReaderApplication

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
        val repoUrl="https://github.com/Steeefanie/personal-reader"
        body.addView(settingRow(Glyph.BOOK,"图书  $version","开源仓库 · github.com/Steeefanie/personal-reader"){
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(repoUrl)))}
                .onFailure{message(repoUrl)}
        })
        body.addView(gap(24))
        val scroll=ScrollView(this).apply{addView(body)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(navigation("设置"){scroll});installRoot(root)
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
