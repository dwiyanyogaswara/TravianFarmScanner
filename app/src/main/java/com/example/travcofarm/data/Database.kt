package com.example.travcofarm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TravcoVillage(val x:Int,val y:Int,val village:String,val account:String,val population:Int,val distance:Double)
data class Oasis(val x:Int,val y:Int,val type:String,val animals:String,val occupied:Boolean,val owner:String,val alliance:String)

class AppDb(context: Context) : SQLiteOpenHelper(context, "targets.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE travco(
            x INTEGER NOT NULL, y INTEGER NOT NULL, village TEXT, account TEXT,
            population INTEGER, distance REAL, PRIMARY KEY(x,y))""")
        db.execSQL("""CREATE TABLE oasis(
            x INTEGER NOT NULL, y INTEGER NOT NULL, type TEXT, animals TEXT,
            occupied INTEGER, owner TEXT, alliance TEXT, PRIMARY KEY(x,y))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun upsertTravco(v: TravcoVillage) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO travco(x,y,village,account,population,distance) VALUES(?,?,?,?,?,?)",
            arrayOf(v.x,v.y,v.village,v.account,v.population,v.distance))
    }
    fun upsertOasis(v: Oasis) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO oasis(x,y,type,animals,occupied,owner,alliance) VALUES(?,?,?,?,?,?,?)",
            arrayOf(v.x,v.y,v.type,v.animals,if(v.occupied)1 else 0,v.owner,v.alliance))
    }
    fun travcoCount() = readableDatabase.rawQuery("SELECT COUNT(*) FROM travco",null).use{it.moveToFirst();it.getInt(0)}
    fun oasisCount() = readableDatabase.rawQuery("SELECT COUNT(*) FROM oasis",null).use{it.moveToFirst();it.getInt(0)}
    fun travcoAll() = readableDatabase.rawQuery("SELECT x,y,village,account,population,distance FROM travco ORDER BY distance",null).use { c ->
        buildList { while(c.moveToNext()) add(TravcoVillage(c.getInt(0),c.getInt(1),c.getString(2) ?: "",c.getString(3) ?: "",c.getInt(4),c.getDouble(5))) }
    }
    fun oasisAll() = readableDatabase.rawQuery("SELECT x,y,type,animals,occupied,owner,alliance FROM oasis ORDER BY y,x",null).use { c ->
        buildList { while(c.moveToNext()) add(Oasis(c.getInt(0),c.getInt(1),c.getString(2) ?: "",c.getString(3) ?: "",c.getInt(4)!=0,c.getString(5) ?: "",c.getString(6) ?: "")) }
    }
    fun clearAll(){ writableDatabase.execSQL("DELETE FROM travco"); writableDatabase.execSQL("DELETE FROM oasis") }
}
