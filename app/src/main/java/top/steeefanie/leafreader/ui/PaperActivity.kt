package top.steeefanie.leafreader.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Explicit activity overrides also suppress transitions supplied by a device's theme. */
open class PaperActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        val internal = intent.component?.packageName == packageName || intent.`package` == packageName
        if (internal) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        super.startActivityForResult(intent, requestCode,
            if (internal) ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle() else options)
        if (internal) overridePendingTransition(0, 0)
    }
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
