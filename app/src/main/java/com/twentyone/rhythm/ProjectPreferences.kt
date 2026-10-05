package com.twentyone.rhythm

import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Business values and their pending changes commit together. Account credentials stay separate. */
object ProjectPreferences {
    internal const val databaseName="project-data.db"
    private val instances=ConcurrentHashMap<String,TransactionalPreferences>()
    private val databases=ConcurrentHashMap<String,PreferenceDatabase>()
    private val process=UUID.randomUUID().toString()
    private const val action="com.twentyone.rhythm.PROJECT_PREFERENCES_CHANGED"
    private var receiverRegistered=false
    private val main=Handler(Looper.getMainLooper())
    fun get(context:Context,name:String):SharedPreferences {
        require(name in setOf("rhythm","coach_chat","coach_voice","coach_reports","habit_reviews","review_settings"))
        val c=context.applicationContext
        synchronized(this) {
            if(!receiverRegistered) {
                ContextCompat.registerReceiver(c,object:BroadcastReceiver() {
                    override fun onReceive(context:Context,intent:Intent) {
                        if(intent.getStringExtra("origin")==process)return
                        val name=intent.getStringExtra("namespace") ?: return
                        val keys=intent.getStringArrayListExtra("keys") ?: return
                        instances.values.filter { it.namespace==name }.forEach { it.notifyChanged(keys) }
                    }
                },IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered=true
            }
        }
        return instances.computeIfAbsent(c.getDatabasePath(databaseName).absolutePath+":"+name) {
            TransactionalPreferences(c,name,database(c)) { keys ->
                c.sendBroadcast(Intent(action).setPackage(c.packageName).putExtra("origin",process)
                    .putExtra("namespace",name).putStringArrayListExtra("keys",ArrayList(keys)))
            }
        }
    }
    internal fun onMain(action:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper())action() else main.post(action) }
    private fun database(c:Context)=databases.computeIfAbsent(c.getDatabasePath(databaseName).absolutePath) { PreferenceDatabase(c.applicationContext,databaseName) }
    internal fun <T> atomic(c:Context,block:()->T):T=SyncJournal.transaction(database(c).writableDatabase,block)
    internal fun changed(c:Context,changes:Map<String,List<String>>) {
        changes.forEach { (name,keys) ->
            instances.values.filter { it.namespace==name }.forEach { it.notifyChanged(keys) }
            c.sendBroadcast(Intent(action).setPackage(c.packageName).putExtra("origin",process).putExtra("namespace",name).putStringArrayListExtra("keys",ArrayList(keys)))
        }
    }
}

internal class PreferenceDatabase(c:Context,name:String):SQLiteOpenHelper(c,name,null,2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE preference_migrations(namespace TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE preferences(namespace TEXT NOT NULL,key TEXT NOT NULL,type TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(namespace,key))")
        db.execSQL("CREATE TABLE preference_changes(sequence INTEGER PRIMARY KEY AUTOINCREMENT,operation_id TEXT NOT NULL UNIQUE,namespace TEXT NOT NULL,key TEXT NOT NULL,type TEXT,value TEXT,scope TEXT NOT NULL,created_at INTEGER NOT NULL,base_revision INTEGER,state TEXT NOT NULL DEFAULT 'queued')")
        db.execSQL("CREATE TABLE preference_sync(namespace TEXT NOT NULL,key TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,baseline TEXT,PRIMARY KEY(namespace,key))")
        db.execSQL("CREATE TABLE sync_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        SyncJournal.create(db)
    }
    override fun onConfigure(db:SQLiteDatabase) { db.rawQuery("PRAGMA busy_timeout=5000",null).use { it.moveToFirst() } }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { if(oldVersion<2)SyncJournal.create(db) }
}

internal data class PreferenceValue(val type:String,val value:String) {
    fun decoded():Any=when(type) {
        "string" -> value
        "int" -> value.toInt()
        "long" -> value.toLong()
        "float" -> value.toFloat()
        "boolean" -> value.toBooleanStrict()
        "set" -> JSONArray(value).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
        else -> error("Unsupported preference value type")
    }
    companion object {
        fun encode(value:Any):PreferenceValue=when(value) {
            is String -> PreferenceValue("string",value)
            is Int -> PreferenceValue("int",value.toString())
            is Long -> PreferenceValue("long",value.toString())
            is Float -> PreferenceValue("float",value.toString())
            is Boolean -> PreferenceValue("boolean",value.toString())
            is Set<*> -> PreferenceValue("set",JSONArray(value.map { it as String }.sorted()).toString())
            else -> error("Unsupported legacy preference type")
        }
    }
}

internal class TransactionalPreferences(
    private val context:Context,
    internal val namespace:String,
    private val helper:PreferenceDatabase,
    private val crossProcess:(List<String>)->Unit={}
):SharedPreferences {
    private val listeners=java.util.Collections.newSetFromMap(java.util.WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener,Boolean>())
    init {
        val db=helper.writableDatabase
        db.beginTransaction()
        try {
            val migrated=db.rawQuery("SELECT 1 FROM preference_migrations WHERE namespace=?",arrayOf(namespace)).use { it.moveToFirst() }
            if(!migrated) {
                // Keep the original XML untouched. An interrupted import rolls back marker, values and queue.
                context.getSharedPreferences(namespace,Context.MODE_PRIVATE).all.forEach { (key,value) ->
                    if(value!=null)write(db,key,PreferenceValue.encode(value))
                }
                db.execSQL("INSERT INTO preference_migrations(namespace) VALUES(?)",arrayOf(namespace))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun read(key:String):PreferenceValue?=helper.readableDatabase.rawQuery("SELECT type,value FROM preferences WHERE namespace=? AND key=?",arrayOf(namespace,key)).use {
        if(it.moveToFirst())PreferenceValue(it.getString(0),it.getString(1)) else null
    }
    override fun getAll():MutableMap<String,*> {
        val result=linkedMapOf<String,Any>()
        helper.readableDatabase.rawQuery("SELECT key,type,value FROM preferences WHERE namespace=?",arrayOf(namespace)).use {
            while(it.moveToNext())result[it.getString(0)]=PreferenceValue(it.getString(1),it.getString(2)).decoded()
        }
        return result
    }
    override fun getString(key:String?,defValue:String?):String?=key?.let(::read)?.decoded()?.let { it as String } ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>?=key?.let(::read)?.decoded()?.let { it as MutableSet<String> } ?: defValues?.toMutableSet()
    override fun getInt(key:String?,defValue:Int):Int=key?.let(::read)?.decoded()?.let { it as Int } ?: defValue
    override fun getLong(key:String?,defValue:Long):Long=key?.let(::read)?.decoded()?.let { it as Long } ?: defValue
    override fun getFloat(key:String?,defValue:Float):Float=key?.let(::read)?.decoded()?.let { it as Float } ?: defValue
    override fun getBoolean(key:String?,defValue:Boolean):Boolean=key?.let(::read)?.decoded()?.let { it as Boolean } ?: defValue
    override fun contains(key:String?):Boolean=key!=null && read(key)!=null
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) { if(listener!=null)synchronized(listeners){listeners.add(listener)} }
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) { synchronized(listeners){listeners.remove(listener)} }
    internal fun notifyChanged(keys:List<String>) {
        ProjectPreferences.onMain {
            val current=synchronized(listeners){listeners.toList()}
            keys.forEach { key -> current.forEach { it.onSharedPreferenceChanged(this,key) } }
        }
    }
    private fun write(db:SQLiteDatabase,key:String,value:PreferenceValue?) {
        if(value==null)db.execSQL("DELETE FROM preferences WHERE namespace=? AND key=?",arrayOf(namespace,key))
        else db.execSQL("INSERT INTO preferences(namespace,key,type,value) VALUES(?,?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET type=excluded.type,value=excluded.value",arrayOf(namespace,key,value.type,value.value))
        val scope=scope(key)
        db.execSQL("INSERT INTO preference_changes(operation_id,namespace,key,type,value,scope,created_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(UUID.randomUUID().toString(),namespace,key,if(scope=="secret")null else value?.type,
                if(scope=="secret")null else value?.value,scope,System.currentTimeMillis()))
    }
    private fun scope(key:String):String {
        if(namespace!="rhythm")return "project"
        if(key in setOf("qr_token","nfc_id"))return "secret"
        if(key in setOf("wake","emergency_night","pass_package","pass_night","pass_day","pass_count","pass_until","scheduled_wake","habit_alarm_at","habit_reminded","habit_timers","bedtime_at","bedtime_locked_nights") ||
            key.startsWith("widget_") || key.startsWith("cards_") || key.startsWith("bedtime_sent_") || key.startsWith("pass_request_"))return "device"
        return "project"
    }
    override fun edit():SharedPreferences.Editor=Editor()
    private inner class Editor:SharedPreferences.Editor {
        private val changes=linkedMapOf<String,PreferenceValue?>()
        private var clear=false
        private fun put(key:String?,value:Any?):SharedPreferences.Editor { requireNotNull(key);changes[key]=value?.let(PreferenceValue::encode);return this }
        override fun putString(key:String?,value:String?):SharedPreferences.Editor=put(key,value)
        override fun putStringSet(key:String?,values:MutableSet<String>?):SharedPreferences.Editor=put(key,values?.toSet())
        override fun putInt(key:String?,value:Int):SharedPreferences.Editor=put(key,value)
        override fun putLong(key:String?,value:Long):SharedPreferences.Editor=put(key,value)
        override fun putFloat(key:String?,value:Float):SharedPreferences.Editor=put(key,value)
        override fun putBoolean(key:String?,value:Boolean):SharedPreferences.Editor=put(key,value)
        override fun remove(key:String?):SharedPreferences.Editor=put(key,null)
        override fun clear():SharedPreferences.Editor { clear=true;return this }
        private fun save() {
            val changed=mutableListOf<String>()
            var syncNeeded=false
            val db=helper.writableDatabase
            db.beginTransaction()
            try {
                val edits=linkedMapOf<String,PreferenceValue?>()
                if(clear)getAll().keys.forEach { edits[it]=null }
                edits.putAll(changes)
                syncNeeded=namespace in SyncCodec.namespaces && edits.any { (key,value) -> read(key)!=value && SyncCodec.scope(namespace,key) !in setOf("secret","runtime") }
                if(syncNeeded)SyncJournal.capture(db,ProjectAccount.device(context))
                edits.forEach { (key,value) -> if(read(key)!=value) { write(db,key,value);changed.add(key) } }
                if(syncNeeded)SyncJournal.capture(db,ProjectAccount.device(context))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            changes.clear();clear=false
            if(changed.isNotEmpty()) { notifyChanged(changed);crossProcess(changed);if(syncNeeded)ProjectSync.schedule(context) }
        }
        override fun commit():Boolean=try { save();true } catch(error:SQLiteException) { false }
        // Synchronous here deliberately: the caller must not report saved before data and queue are durable.
        override fun apply() { save() }
    }
}
