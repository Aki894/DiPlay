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
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var holder: SurfaceHolder? = null
    private val refresh=object : Runnable { override fun run() {
        val view=holder
        val next=BoardService.instance?.sink
        if (view?.surface?.isValid==true && attached!==next) {
            detach()
            if(next!=null) {
                attached=next;surface=view.surface
                next.setSurface(110,view.surface);next.setLocalPreviewEnabled(true)
            }
        }
        handler.postDelayed(this,1000)
    } }
    override fun onStart() { super.onStart();handler.post(refresh) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(SurfaceView(this).also { it.holder.addCallback(this) })
    }
    override fun surfaceCreated(holder: SurfaceHolder) { this.holder=holder;handler.removeCallbacks(refresh);handler.post(refresh) }
    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { this.holder=null;detach() }
    override fun onStop() { handler.removeCallbacksAndMessages(null);detach();super.onStop() }
    private fun detach() {
        attached?.let { s -> surface?.let { s.clearSurface(110,it) }; s.setLocalPreviewEnabled(false) }
        attached=null; surface=null
    }
}
