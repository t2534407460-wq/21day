package com.twentyone.rhythm

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class TransactionalPreferencesTest {
    private val c=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var helper:PreferenceDatabase
    @Before fun prepare() {
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        name="qa-${UUID.randomUUID()}"
        helper=PreferenceDatabase(c,"$name.db")
    }
    @After fun cleanup() { if(::helper.isInitialized) { helper.close();c.deleteDatabase("$name.db");c.deleteSharedPreferences(name) } }
    @Test fun nonemptyLegacyImportPreservesTypesAndIsIdempotent() {
        val old=c.getSharedPreferences(name,0)
        assertTrue(old.edit().putString("chat","中文\n旧聊天").putInt("count",4).putLong("timer",1234567890123)
            .putFloat("scale",1.25f).putBoolean("weekly",false).putStringSet("days",mutableSetOf("一","三")).commit())
        val values=TransactionalPreferences(c,name,helper)
        assertEquals(old.all,values.all)
        assertEquals(6,helper.readableDatabase.rawQuery("SELECT count(*) FROM preference_changes",null).use { it.moveToFirst();it.getInt(0) })
        assertTrue(values.edit().putInt("count",5).putString("layout","green").commit())
        val reopened=TransactionalPreferences(c,name,helper)
        assertEquals(5,reopened.getInt("count",0));assertEquals("green",reopened.getString("layout",null))
        assertEquals(4,old.getInt("count",0))
        assertEquals(8,helper.readableDatabase.rawQuery("SELECT count(*) FROM preference_changes",null).use { it.moveToFirst();it.getInt(0) })
        val copy=values.getStringSet("days",null)!!;copy.add("五")
        assertEquals(setOf("一","三"),values.getStringSet("days",null))
    }
    @Test fun queueFailureRollsBackAllBusinessValuesAndDeleteKeepsTombstone() {
        val values=TransactionalPreferences(c,name,helper)
        assertTrue(values.edit().putString("plan","original").putString("logs","kept").commit())
        helper.writableDatabase.execSQL("CREATE TRIGGER qa_fail BEFORE INSERT ON preference_changes BEGIN SELECT RAISE(ABORT,'synthetic queue failure'); END")
        assertFalse(values.edit().putString("plan","changed").remove("logs").commit())
        assertEquals("original",values.getString("plan",null));assertEquals("kept",values.getString("logs",null))
        helper.writableDatabase.execSQL("DROP TRIGGER qa_fail")
        assertTrue(values.edit().remove("logs").commit());assertFalse(values.contains("logs"))
        helper.readableDatabase.rawQuery("SELECT type,value FROM preference_changes WHERE key='logs' ORDER BY sequence DESC LIMIT 1",null).use {
            assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertTrue(it.isNull(1))
        }
    }
    @Test fun interruptedMigrationPreservesOriginalAndDoesNotMarkImported() {
        val original=c.getSharedPreferences(name,0)
        assertTrue(original.edit().putString("first","original").putString("second","retained").commit())
        helper.writableDatabase.execSQL("CREATE TRIGGER qa_fail BEFORE INSERT ON preference_changes BEGIN SELECT RAISE(ABORT,'synthetic migration failure'); END")
        try { TransactionalPreferences(c,name,helper);fail("Migration must fail") } catch(expected:android.database.sqlite.SQLiteException) { }
        for(table in listOf("preferences","preference_changes","preference_migrations")) {
            assertEquals(0,helper.readableDatabase.rawQuery("SELECT count(*) FROM $table",null).use { it.moveToFirst();it.getInt(0) })
        }
        assertEquals("original",original.getString("first",null))
        helper.writableDatabase.execSQL("DROP TRIGGER qa_fail")
        assertEquals(original.all,TransactionalPreferences(c,name,helper).all)
    }
    @Test fun independentConnectionsObserveWritesWithoutStalePreferenceCache() {
        val first=TransactionalPreferences(c,name,helper)
        val otherHelper=PreferenceDatabase(c,"$name.db")
        try {
            val second=TransactionalPreferences(c,name,otherHelper)
            assertTrue(first.edit().putLong("timer",1234L).commit())
            assertEquals(1234L,second.getLong("timer",0))
            assertTrue(second.edit().putLong("timer",5678L).putBoolean("enabled",true).commit())
            assertEquals(5678L,first.getLong("timer",0));assertTrue(first.getBoolean("enabled",false))
        } finally { otherHelper.close() }
    }
}
