package com.twentyone.rhythm

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.*
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NightAccessibilityService : AccessibilityService() {
    private val handler=Handler(Looper.getMainLooper())
    private var overlay: ScrollView?=null
    private var preview=false
    private var shownPackage=""
    private var shownDay: LocalDate?=null
    private var shownRemaining=-1
    private val ticker=object:Runnable { override fun run() { reconcile(); handler.postDelayed(this,1000) } }
    override fun onServiceConnected() { instance=this; handler.post(ticker) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(!preview) reconcile()
    }
    private fun foregroundPackage():String {
        // Background apps can still emit window events after HOME. Only inspect the visible window's package.
        val visible=windows.filter { it.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        // Our touchable overlay can take focus; getWindows still exposes the application directly below it.
        val window=visible.firstOrNull { it.isActive || it.isFocused }
            ?: visible.firstOrNull { it.type==AccessibilityWindowInfo.TYPE_APPLICATION } ?: return ""
        val root=window.root ?: return ""
        return try { root.packageName?.toString() ?: "" } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    private fun reconcile() {
        if(preview) return
        val currentPackage=foregroundPackage()
        val s=Store(this); val hadPending=s.pendingAt>0;s.applyPending()
        if(hadPending && s.pendingAt==0L) Alarms.schedule(this)
        val n=s.plan?.let { Schedule.night(it,s.rules,LocalDateTime.now()) }
        val blocked=!isProtected(currentPackage) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            Schedule.blocks(currentPackage,s.rules,n,s.passPackage,s.passUntil,System.currentTimeMillis())
        if(blocked) { if(overlay==null || shownPackage!=currentPackage || shownDay!=LocalDate.now() || shownRemaining!=s.passesRemaining()) showBlock(currentPackage,false) } else hide()
    }
    fun isProtected(pkg: String): Boolean {
        if(pkg==packageName || pkg=="android" || pkg.startsWith("com.android.systemui") || pkg.contains("permissioncontroller") || pkg=="com.android.settings") return true
        val home=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        val dialer=getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        return pkg==home || pkg==dialer || pkg in setOf("com.android.deskclock","com.google.android.deskclock","com.android.phone","com.android.emergency")
    }
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    private val paper=Color.rgb(247,245,239)
    private val ink=Color.rgb(40,59,50)
    private val moss=Color.rgb(83,101,71)
    private val muted=Color.rgb(102,112,98)
    private val sage=Color.rgb(231,236,223)
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun text(value: String,size: Float,color: Int=ink)=TextView(this).apply { text=value; textSize=size; setTextColor(color); setLineSpacing(dp(3).toFloat(),1f) }
    private fun button(value: String,primary:Boolean,click:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;textSize=16f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        minHeight=dp(56);minimumHeight=dp(56);setPadding(dp(16),dp(14),dp(16),dp(14));setTextColor(if(primary)Color.WHITE else moss)
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(35,83,101,71)),rounded(if(primary)moss else sage,18),null)
        stateListAnimator=null;elevation=0f
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) }
        setOnClickListener { click() }
    }
    fun showBlock(pkg: String,isPreview: Boolean) {
        hide(); preview=isPreview; shownPackage=pkg
        val s=Store(this)
        val now=LocalDateTime.now();val n=s.plan?.let { Schedule.night(it,s.rules,now) }
        val remaining=if(isPreview)Store.DAILY_PASSES else s.passesRemaining(now.toLocalDate())
        shownDay=now.toLocalDate();shownRemaining=remaining
        val name=runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault("娱乐应用")
        val scroll=ScrollView(this).apply { isFillViewport=true;setBackgroundColor(paper);isVerticalScrollBarEnabled=false }
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(28),dp(28),dp(28),dp(28)) }
        scroll.addView(layout,FrameLayout.LayoutParams(-1,-1))
        layout.addView(text(if(isPreview)"廿一 / 效果预览" else "廿一 / 夜间休息",14f,moss))
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(36),0,dp(24)) }
        layout.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(text("休息一下，\n明天继续。",32f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setPadding(0,0,0,dp(14)) })
        content.addView(text(if(isPreview)"到了约定的休息时间，\n受限应用会暂时停在这里。" else "到了约定的休息时间，\n给今天留一个轻松的收尾。",16f,muted))
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=rounded(Color.WHITE,24);setPadding(dp(20),dp(20),dp(20),dp(20)) }
        content.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(28) })
        val appRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        if(!isPreview)runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { icon ->
            appRow.addView(ImageView(this).apply { setImageDrawable(icon);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(14) })
        }
        appRow.addView(text(if(isPreview)"受限应用" else name,18f).apply { typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f))
        card.addView(appRow)
        card.addView(text(n?.let { "${it.end.format(DateTimeFormatter.ofPattern("HH:mm"))} 后恢复使用" } ?: "休息时段结束后恢复使用",14f,muted).apply { setPadding(0,dp(12),0,dp(18)) })
        card.addView(View(this).apply { setBackgroundColor(sage) },LinearLayout.LayoutParams(-1,dp(1)))
        card.addView(text("今日剩余 $remaining / ${Store.DAILY_PASSES} 次",17f,moss).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setPadding(0,dp(18),0,dp(6)) })
        card.addView(text(if(remaining>0)"每次 5 分钟，无需等待\n所有受限应用共用，每天 0 点重置" else "今天的临时使用次数已用完\n明天 0 点恢复额度",13f,muted))
        layout.addView(button(if(isPreview)"结束预览" else "回到桌面",true) {
            preview=false;if(!isPreview)performGlobalAction(GLOBAL_ACTION_HOME);hide()
        })
        if(!isPreview && n!=null && remaining>0)layout.addView(button("临时使用 5 分钟",false) {
            if(s.grantPass(pkg))hide()else showBlock(pkg,false)
        })
        layout.addView(text(if(isPreview)"预览不会消耗次数或更改计划" else "电话、闹钟等必要功能仍可使用",12f,muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(18),0,0) })
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        overlay=scroll
        val window=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
        }
        getSystemService(WindowManager::class.java).addView(scroll,window)
    }
    private fun hide() { overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay=null; shownPackage="";preview=false }
    override fun onInterrupt() { hide() }
    override fun onDestroy() { instance=null; handler.removeCallbacksAndMessages(null); hide(); super.onDestroy() }
    companion object {
        private var reference=java.lang.ref.WeakReference<NightAccessibilityService>(null)
        var instance: NightAccessibilityService?
            get()=reference.get()
            private set(value){reference=java.lang.ref.WeakReference(value)}
    }
}
