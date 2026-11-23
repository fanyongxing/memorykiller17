package com.magicalstory.memorykiller17.nativecore

object NativeMemoryEater {
    init { System.loadLibrary("eatmem") }
    external fun startEating(threadCount: Int, chunkMb: Int)
    external fun stopAndRelease()
    external fun getAllocatedMb(): Long
}
