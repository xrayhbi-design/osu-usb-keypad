package app.osukeypad

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var pad: PadView
    private lateinit var link: Link

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        window.setDecorFitsSystemWindows(false)
        link = Link { pad.postInvalidate() }
        pad = PadView(this, link)
        setContentView(pad)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        link.start()
    }

    override fun onPause() {
        pad.releaseAll()      // sends key-ups while the link is still up; the PC also releases on disconnect
        link.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
