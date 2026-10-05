package com.twentyone.rhythm

import kotlin.math.*

// Touches near the centre or outside the choices cancel; merely holding never saves.
object OrbSelection {
    fun target(x:Float,y:Float,radius:Float,count:Int):Int? {
        if(radius<=0 || count<1 || hypot(x,y)<radius*.55f || hypot(x,y)>radius*1.65f) return null
        val nearest=(0 until count).minBy { i ->
            val angle=-PI/2+2*PI*i/count
            hypot(x-radius*cos(angle).toFloat(),y-radius*sin(angle).toFloat())
        }
        val angle=-PI/2+2*PI*nearest/count
        return nearest.takeIf { hypot(x-radius*cos(angle).toFloat(),y-radius*sin(angle).toFloat())<=radius*.66f }
    }
}
