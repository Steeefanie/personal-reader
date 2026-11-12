package top.steeefanie.leafreader.drive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.ui.*
import top.steeefanie.leafreader.ui.PaperPanel as AlertDialog
import java.text.DateFormat
import java.util.Date

/** Shared account page. The caller stays on the Activity stack, with its scroll state intact. */
class DriveConnectionActivity:PaperActivity() {
    private val app get()=application as ReaderApplication
    private lateinit var address:EditText
    private lateinit var addressRow:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var state:TextView
    private var operation:Job?=null
    private var polling:Job?=null
    private var bindingDialog:AlertDialog?=null
    private var bindingBitmap:Bitmap?=null
    private val childPanels=mutableSetOf<AlertDialog>()
    private fun stateName(value:String)=when(value) {
        "connected"->"已授权";"revoked"->"已撤销";"unbound"->"已解绑";else->"需要重新绑定"
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        address=EditText(this).apply {
            hint="你的云盘域名";isSingleLine=true;textSize=PaperText.BODY;minHeight=dp(48)
            pad(4,8);background=null
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        addressRow=row().apply {
            pad(12,0);background=paperBackground(Color.WHITE,Color.GRAY)
            addView(label("https://",PaperText.BODY).apply { setTextColor(Paper.secondary) })
            addView(address,LinearLayout.LayoutParams(0,-2,1f))
        }
        state=label("",PaperText.DETAIL).apply { visibility=View.GONE;setTextColor(Paper.secondary);pad(0,8) }
        content=column().apply { pad(20,12) }
        scroll=ScrollView(this).apply { addView(content) }
        val root=column()
        root.addView(pageHeader("连接与账号"))
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(rule(0))
        root.addView(row().apply {
            pad(8,0)
            addView(readerBackButton("返回") { finish() })
            addView(Space(context),LinearLayout.LayoutParams(0,1,1f))
            addView(pageControls { scroll })
        },LinearLayout.LayoutParams(-1,dp(Paper.BOTTOM_BAR_HEIGHT)))
        installRoot(root)
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { showConnection() }
    }
    private suspend fun showConnection() {
        val connection=app.drive.connection()
        val pending=app.drive.pending()
        val previousScroll=scroll.scrollY
        content.removeAllViews()
        if(connection!=null) {
            content.addView(label(connection.userName,PaperText.FEATURE_TITLE).strong())
            content.addView(gap(8))
            content.addView(label("${connection.deviceName} · ${stateName(connection.status)}",PaperText.DETAIL).apply { setTextColor(Paper.secondary) })
            content.addView(gap(20))
            if(!address.hasFocus())address.setText(connection.baseUrl.removePrefix("https://"))
        }
        content.addView(label("云盘地址",PaperText.DETAIL).apply { setTextColor(Paper.secondary) })
        content.addView(gap(8));content.addView(addressRow);content.addView(gap(12))
        content.addView(button(if(connection==null)"扫码连接" else "重新绑定设备",true) { bind() })
        content.addView(state)
        if(connection!=null) {
            content.addView(gap(16));content.addView(rule(0))
            content.addView(label("最近同步：${if(connection.lastSync==0L)"尚未同步" else DateFormat.getDateTimeInstance().format(Date(connection.lastSync))}\n待同步 $pending 项",PaperText.DETAIL).apply {
                setTextColor(Paper.secondary);setPadding(0,dp(16),0,dp(8))
            })
            content.addView(settingRow(Glyph.CLOSE,"解除设备绑定","") { unbind() }.apply { pad(0,14) })
        }
        scroll.post { scroll.scrollTo(0,previousScroll) }
    }
    private fun AlertDialog.Builder.open():AlertDialog=create().also { panel ->
        childPanels+=panel
        panel.setOnDismissListener { childPanels-=panel }
        panel.show()
    }
    private suspend fun synchronizeConnection() {
        app.drive.synchronize()
        withContext(Dispatchers.IO) { app.drive.refreshDirectory() }
    }
    private fun bind() {
        if (operation?.isActive == true || polling?.isActive == true) { message("请先完成或取消当前操作"); return }
        task {
            val client = DriveClient(address.text.toString())
            val binding = client.createBinding(packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0")
            val bitmap = withContext(Dispatchers.Default) {
                val qr = QrCode.encodeText(binding.verificationUrl, QrCode.Ecc.MEDIUM)
                val scale = maxOf(2, 480 / (qr.size + 8)); val side = (qr.size + 8) * scale
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
                    val pixels = IntArray(side * side) { i -> if (qr.getModule(i % side / scale - 4, i / side / scale - 4)) Color.BLACK else Color.WHITE }
                    setPixels(pixels, 0, side, 0, 0, side, side)
                }
            }
            val box = column().apply {
                pad(20,16)
                addView(ImageView(this@DriveConnectionActivity).apply { setImageBitmap(bitmap); contentDescription = "设备绑定二维码" }, LinearLayout.LayoutParams(-1, dp(260)))
                addView(label("核对码：${binding.confirmationCode}\n请用手机扫码，以自己的通行密钥确认设备和权限。",PaperText.ITEM_TITLE))
                addView(label(binding.verificationUrl,PaperText.DETAIL).apply { setTextIsSelectable(true) })
                addView(button("在本机浏览器授权") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(binding.verificationUrl))) })
            }
            val dialog = AlertDialog.Builder(this@DriveConnectionActivity).setTitle("扫码绑定设备").fullScreen().setView(ScrollView(this@DriveConnectionActivity).apply { addView(box) }).create()
            bindingDialog = dialog; bindingBitmap = bitmap
            dialog.setOnDismissListener { polling?.cancel(); polling = null; bindingDialog = null; bindingBitmap = null; if (!bitmap.isRecycled) bitmap.recycle() }
            dialog.show()
            polling = lifecycleScope.launch {
                try {
                    val expires = SystemClock.elapsedRealtime() + binding.expiresIn * 1000L
                    var interval = binding.interval
                    while (SystemClock.elapsedRealtime() < expires) {
                        delay(minOf(interval * 1000L, (expires - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
                        if (SystemClock.elapsedRealtime() >= expires) break
                        val result = try { client.poll(binding) }
                        catch (e: DriveException) {
                            if (e.status == 429) { interval = maxOf(interval, ((e.retryAfterMillis + 999) / 1000).toInt().coerceAtLeast(5)); continue }
                            if (e.status >= 500) { interval = (interval * 2).coerceAtMost(30); continue }
                            throw e
                        } catch (_: java.io.IOException) { interval = (interval * 2).coerceAtMost(30); continue }
                        when (result.status) {
                            "pending" -> interval = result.interval
                            "slow_down" -> interval = maxOf(interval + 5, result.interval).coerceAtMost(300)
                            "approved" -> {
                                val connection = withContext(Dispatchers.IO) { app.drive.bind(client.origin, result) }
                                dialog.setOnDismissListener(null); dialog.dismiss(); bitmap.recycle(); polling = null; bindingDialog = null; bindingBitmap = null
                                showConnection()
                                task { app.repository.adoptLocal(connection.ownerKey); synchronizeConnection() }
                                return@launch
                            }
                            else -> { message(when (result.status) { "denied" -> "授权被拒绝"; "expired" -> "二维码已过期，请重新生成"; else -> "授权结果已被领取，请重新绑定" }); dialog.dismiss(); return@launch }
                        }
                    }
                    message("二维码已过期，请重新生成"); dialog.dismiss()
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { message(safeError(e)); dialog.dismiss() }
            }
        }
    }
    private fun unbind() {
        AlertDialog.Builder(this).setTitle("解除当前设备绑定？")
            .setMessage("正常解除会向云盘撤销此设备并清除本机凭证，本机已有的书籍与阅读进度将继续保留。")
            .setPositiveButton("撤销并解除") { _, _ -> task { app.drive.revoke() } }
            .setNeutralButton("仅本机忘记") { _, _ ->
                AlertDialog.Builder(this).setTitle("仅清除本机凭证")
                    .setMessage("这不会撤销云盘上的设备权限。之后请到云盘安全页撤销旧设备。")
                    .setPositiveButton("清除") { _, _ -> task { app.drive.revoke(localOnly = true) } }.setNegativeButton("取消", null).open()
            }.setNegativeButton("取消", null).open()
    }
    private fun task(action: suspend () -> Unit) {
        if (operation?.isActive == true) { message("正在处理，请稍候"); return }
        state.text="";state.visibility=View.GONE
        operation = lifecycleScope.launch {
            try { action(); showConnection() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { state.text = safeError(e);state.visibility=View.VISIBLE;message(safeError(e)) }
        }
    }
    private fun safeError(e: Exception): String = when (e) {
        is DriveException -> if (e.status == 404 && app.repository.owner.value == "local") "云盘尚未提供阅读器接口，或地址不正确" else e.message.orEmpty()
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "云盘响应校验失败"
        else -> "连接失败，请检查网络和接口状态；本地数据与同步队列已保留"
    }
    override fun onDestroy() {
        polling?.cancel()
        bindingDialog?.setOnDismissListener(null);bindingDialog?.dismiss()
        bindingBitmap?.let { if(!it.isRecycled)it.recycle() }
        childPanels.toList().forEach { it.setOnDismissListener(null);it.dismiss() }
        childPanels.clear()
        super.onDestroy()
    }
}
