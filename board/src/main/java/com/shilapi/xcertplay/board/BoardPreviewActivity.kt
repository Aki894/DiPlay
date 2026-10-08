package com.shilapi.xcertplay.board

import android.app.Activity
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Debug only: closing the screen frees its decoder and leaves the service-owned session alive. */
class BoardPreviewActivity : Activity(), SurfaceHolder.Callback {
    private var attached: com.shilapi.xcertplay.media.AndroidMediaSink? = null
    private var surface: Surface? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(SurfaceView(this).also { it.holder.addCallback(this) })
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        val sink=BoardService.instance?.sink ?: return
        attached=sink; surface=holder.surface
        sink.setSurface(110,holder.surface); sink.setLocalPreviewEnabled(true)
    }
    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { detach() }
    override fun onStop() { detach(); super.onStop() }
    private fun detach() {
        attached?.let { s -> surface?.let { s.clearSurface(110,it) }; s.setLocalPreviewEnabled(false) }
        attached=null; surface=null
    }
}
