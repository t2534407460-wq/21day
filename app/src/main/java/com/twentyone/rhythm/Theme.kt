package com.twentyone.rhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Paper=Color(0xFFF7F5EF)
val Ink=Color(0xFF283B32)
val Muted=Color(0xFF727A6C)
val Sage=Color(0xFFE7ECDF)
val Moss=Color(0xFF536547)
val Lime=Color(0xFFE0E9AC)
val Clay=Color(0xFFAD6850)
@Composable fun RhythmTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Moss,onPrimary=Color.White,secondary=Clay,background=Paper,surface=Paper,onSurface=Ink,onBackground=Ink,surfaceVariant=Sage,outline=Color(0xFFD2D8CB)),typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=16.sp,lineHeight=25.sp),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp,lineHeight=22.sp)),content=content)
}
@Composable fun Sheet(modifier:Modifier=Modifier,color:Color=Color.White,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().background(color,RoundedCornerShape(24.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
@Composable fun Eyebrow(text:String,color:Color=Muted) { Text(text,color=color,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Medium) }
@Composable fun Heading(text:String) { Text(text,fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=Ink,lineHeight=37.sp) }
@Composable fun LargeNumber(text:String,color:Color=Ink,size:Int=38) { Text(text,fontSize=size.sp,fontFamily=FontFamily.Serif,color=color) }
@Composable fun Primary(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,click:()->Unit) { Button(onClick=click,modifier=modifier.fillMaxWidth().heightIn(min=52.dp),enabled=enabled,shape=RoundedCornerShape(16.dp)) { Text(text,fontSize=15.sp) } }
@Composable fun SmallNote(text:String) { Text(text,color=Muted,fontSize=12.sp,lineHeight=19.sp) }
