package com.twentyone.rhythm

/** Elapsed realtime only: changing the wall clock cannot shorten the wait. */
class EmergencyExitGate(private val startedAt:Long) {
    private var heldAt:Long?=null
    fun waitRemaining(now:Long)=(60_000L-(now-startedAt)).coerceIn(0,60_000L)
    fun beginHold(now:Long):Boolean {
        if(waitRemaining(now)>0) return false
        heldAt=now;return true
    }
    fun cancelHold(){heldAt=null}
    fun heldMillis(now:Long)=heldAt?.let{(now-it).coerceIn(0,5_000L)} ?: 0L
    fun canExit(now:Long)=waitRemaining(now)==0L && heldMillis(now)==5_000L
}
