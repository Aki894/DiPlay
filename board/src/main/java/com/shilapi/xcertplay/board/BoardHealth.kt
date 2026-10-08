package com.shilapi.xcertplay.board

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/** Fifteen-second, bounded samples; no media bytes, keys or device identities. */
class BoardHealth(context: Context) {
    private val samples=ArrayDeque<JSONObject>()
    private val file=File(context.filesDir,"board-health.jsonl")
    private var activeSamples=0
    private var peakPss=0
    private var peakHeap=0L
    private var lastState=""
    private var transitions=0
    @Synchronized fun record(state:String,stats:LongArray) {
        val memory=Debug.MemoryInfo().also {Debug.getMemoryInfo(it)}
        val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        peakPss=maxOf(peakPss,memory.totalPss);peakHeap=maxOf(peakHeap,heap)
        if(state!=lastState) {transitions++;lastState=state}
        if(state in setOf("WirelessActive","active"))activeSamples++
        val sample=JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()).put("state",state)
            .put("pssKiB",memory.totalPss).put("heapBytes",heap)
            .put("threads",File("/proc/self/task").list()?.size ?: -1)
            .put("fileDescriptors",File("/proc/self/fd").list()?.size ?: -1)
            .put("videoFrames",stats[0]).put("videoBytes",stats[1]).put("videoDecoders",stats[2])
        samples.addLast(sample)
        while(samples.size>240)samples.removeFirst()
        runCatching {
            if(file.length()>512*1024) {
                val previous=File(file.parentFile,"board-health.previous.jsonl")
                previous.delete();file.renameTo(previous)
            }
            file.appendText(sample.toString()+"\n")
        }
    }
    @Synchronized fun summary()=JSONObject().put("sampleIntervalSeconds",15).put("sampleCount",samples.size)
        .put("activeSamples",activeSamples).put("peakPssKiB",peakPss).put("peakHeapBytes",peakHeap)
        .put("stateTransitions",transitions).put("lastSample",samples.peekLast())
    @Synchronized fun export()=samples.joinToString("\n")
}
