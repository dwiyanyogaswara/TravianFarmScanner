package com.example.travcofarm

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.travcofarm.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var runner: WebViewRunner
    private lateinit var db: AppDb
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var server: EditText
    private lateinit var xEdit: EditText
    private lateinit var yEdit: EditText
    private lateinit var radiusEdit: EditText
    private lateinit var farmList: EditText
    private lateinit var troop: EditText
    private lateinit var troopCount: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.webView)
        runner = WebViewRunner(web)
        db = AppDb(this)
        status=findViewById(R.id.tvStatus); logView=findViewById(R.id.tvLog)
        server=findViewById(R.id.etServer); xEdit=findViewById(R.id.etX); yEdit=findViewById(R.id.etY); radiusEdit=findViewById(R.id.etRadius)
        farmList=findViewById(R.id.etFarmList); troop=findViewById(R.id.etTroop); troopCount=findViewById(R.id.etTroopCount)
        radiusEdit.setText("30")
        loadPrefs()

        findViewById<Button>(R.id.btnLogin).setOnClickListener { login() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }
        findViewById<Button>(R.id.btnScanTravco).setOnClickListener { scanTravco() }
        findViewById<Button>(R.id.btnScanOasis).setOnClickListener { scanOasis() }
        findViewById<Button>(R.id.btnFarmTravco).setOnClickListener { addFarmlist(false) }
        findViewById<Button>(R.id.btnFarmOasis).setOnClickListener { addFarmlist(true) }
        refreshCounts()
    }

    private fun log(s:String) {
        runOnUiThread {
            logView.text = "${logView.text}\n$s".takeLast(7000)
        }
    }
    private fun setBusy(b:Boolean) {
        runOnUiThread {
            findViewById<Button>(R.id.btnLogin).isEnabled=!b
            findViewById<Button>(R.id.btnLogout).isEnabled=!b
            findViewById<Button>(R.id.btnScanTravco).isEnabled=!b
            findViewById<Button>(R.id.btnScanOasis).isEnabled=!b
            findViewById<Button>(R.id.btnFarmTravco).isEnabled=!b
            findViewById<Button>(R.id.btnFarmOasis).isEnabled=!b
        }
    }
    private fun setStatus(s:String){ runOnUiThread{status.text="Status: $s"} }
    private fun refreshCounts(){ findViewById<TextView>(R.id.tvTravcoCount).text="Travco DB: ${db.travcoCount()}"; findViewById<TextView>(R.id.tvOasisCount).text="Oasis DB: ${db.oasisCount()}" }

    private fun login() {
        savePrefs()
        lifecycleScope.launch {
            setBusy(true)
            try {
                val host=normalizeServer()
                runner.load("https://$host/")
                setStatus("halaman Travian terbuka — silakan login di WebView")
                web.visibility=View.VISIBLE
                log("LOGIN: buka Travian")
            } catch(e:Exception){ log("LOGIN ERROR: ${e.message}") }
            finally{setBusy(false)}
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val host=normalizeServer()
                runner.load("https://$host/")
                // Prefer Travian's own logout DOM action before clearing the browser session.
                runner.js("""(() => {
                    const a=[...document.querySelectorAll('a,button')].find(e =>
                      /logout|log out|ausloggen|déconnexion/i.test((e.textContent||'')+' '+(e.getAttribute('title')||'')) ||
                      /logout/i.test(e.getAttribute('href')||''));
                    if(a){a.click();return true} return false;
                })()""")
                kotlinx.coroutines.delay(700)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                setStatus("logout")
                log("LOGOUT: session Travian dihapus")
            } catch(e:Exception){ log("LOGOUT ERROR: ${e.message}") }
            finally{setBusy(false)}
        }
    }

    private fun scanTravco() {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val host=normalizeServer(); val cx=xEdit.intValue(); val cy=yEdit.intValue()
                setStatus("scan Travco berjalan")
                runner.load("https://travcotools.com/en/inactive-search/")
                val hostJs=JSONObject.quote(host)
                val state = runner.jsString("""(() => {
                    const sel=document.querySelector('#id_travian_server');
                    const opt=[...sel.options].find(o=>(o.textContent||'').trim().toLowerCase()===$hostJs.toLowerCase());
                    if(!opt) return JSON.stringify({ok:false,error:'Server tidak ditemukan di Travco'});
                    sel.value=opt.value;
                    const set=(id,v)=>{const e=document.querySelector(id);e.value=String(v);e.dispatchEvent(new Event('change',{bubbles:true}));};
                    set('#id_x',$cx); set('#id_y',$cy); set('#id_days',7); set('#id_order_by','distance'); set('#id_page_size',100);
                    const f=sel.form || document.querySelector('form');
                    if(!f) return JSON.stringify({ok:false,error:'Form Travco tidak ditemukan'});
                    f.requestSubmit ? f.requestSubmit() : f.submit();
                    return JSON.stringify({ok:true});
                })()""")
                if(!JSONObject(state).optBoolean("ok")) throw IllegalStateException(JSONObject(state).optString("error"))
                waitForPageTable()
                var page=1
                while(true) {
                    val payload=runner.jsString(TRAVCO_SCRAPE_JS)
                    val obj=JSONObject(payload)
                    val rows=obj.optJSONArray("rows") ?: JSONArray()
                    for(i in 0 until rows.length()){
                        val r=rows.getJSONObject(i)
                        val coords=parseCoords(r.optString("href") + " " + r.optString("coordinates"))
                        if(coords!=null) db.upsertTravco(TravcoVillage(coords.first,coords.second,r.optString("village"),r.optString("account"),r.optInt("population"),r.optDouble("distance")))
                    }
                    val next=obj.optString("nextHref","")
                    log("TRAVCO page $page: ${rows.length()} target")
                    if(next.isBlank()) break
                    page++; runner.load(if(next.startsWith("http")) next else "https://travcotools.com$next"); waitForPageTable()
                    if(page>100) break
                }
                refreshCounts(); setStatus("scan Travco selesai")
            } catch(e:Exception){log("TRAVCO ERROR: ${e.message}");setStatus("error")}
            finally{setBusy(false)}
        }
    }

    private suspend fun waitForPageTable(){
        repeat(30){
            val ok=runner.jsBoolean("!!document.querySelector('main table')")
            if(ok) return
            kotlinx.coroutines.delay(300)
        }
        throw IllegalStateException("Tabel Travco belum muncul")
    }

    private fun scanOasis() {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val host=normalizeServer(); val cx=xEdit.intValue(); val cy=yEdit.intValue(); val radius=radiusEdit.intValue().coerceIn(1,200)
                runner.load("https://$host/karte.php")
                setStatus("scan Oasis berjalan")
                val minX=max(-200,cx-radius); val maxX=min(200,cx+radius)
                val minY=max(-200,cy-radius); val maxY=min(200,cy+radius)
                val centers=scanCenters(minX,maxX,minY,maxY,15)
                var done=0
                for((sx,sy) in centers){
                    val js=OASIS_FETCH_JS.replace("__X__",sx.toString()).replace("__Y__",sy.toString())
                    val text=runner.jsString(js)
                    parseOasis(text,minX,maxX,minY,maxY)
                    done++
                    if(done%5==0) log("OASIS: $done/${centers.size}")
                    kotlinx.coroutines.delay(Random.nextLong(800,1800))
                }
                refreshCounts(); setStatus("scan Oasis selesai")
                log("OASIS selesai: ${db.oasisCount()} data")
            } catch(e:Exception){log("OASIS ERROR: ${e.message}");setStatus("error")}
            finally{setBusy(false)}
        }
    }

    private fun parseOasis(text:String,minX:Int,maxX:Int,minY:Int,maxY:Int){
        val root=JSONObject(text); val tiles=root.optJSONArray("tiles") ?: return
        for(i in 0 until tiles.length()){
            val t=tiles.getJSONObject(i); val x=coord(t,"x"); val y=coord(t,"y")
            if(x !in minX..maxX || y !in minY..maxY) continue
            if(t.optInt("did",0)!=-1) continue
            val title=t.optString("title"); if(title!=" {k.fo}".trim() && title!="{k.fo}" && title!="{k.bt}") continue
            val clean=t.optString("text").replace(Regex("[\\u202A-\\u202E\\u2066-\\u2069]"),"")
            val type=mapOasisType(clean) ?: continue
            val occupied=title=="{k.bt}" || t.has("uid")
            val animals=if(!occupied) parseAnimals(clean) else ""
            val owner=if(occupied) token(clean,"player") else ""
            val alliance=if(occupied) token(clean,"alliance") else ""
            db.upsertOasis(Oasis(x,y,type,animals,occupied,owner,alliance))
        }
    }

    private fun mapOasisType(s:String):String? {
        val b=Regex("""(?:bonus|k\.bonus|rsc)(?:[^0-9]{0,30})([1-4])(?:[^0-9]{0,10})([25]0)%""",RegexOption.IGNORE_CASE).findAll(s)
            .map{it.groupValues[1].toInt() to it.groupValues[2].toInt()}.toSet()
        return when(b) {
            setOf(1 to 25)->"Wood 25%"; setOf(1 to 50)->"Wood 50%"
            setOf(2 to 25)->"Clay 25%"; setOf(2 to 50)->"Clay 50%"
            setOf(3 to 25)->"Iron 25%"; setOf(3 to 50)->"Iron 50%"
            setOf(4 to 25)->"Crop 25%"; setOf(4 to 50)->"Crop 50%"
            setOf(1 to 25,4 to 25)->"Wood+Crop"
            setOf(2 to 25,4 to 25)->"Clay+Crop"
            setOf(3 to 25,4 to 25)->"Iron+Crop"
            else->null
        }
    }
    private fun parseAnimals(s:String):String {
        val names=mapOf(31 to "Rat",32 to "Spider",33 to "Snake",34 to "Bat",35 to "Wild Boar",36 to "Wolf",37 to "Bear",38 to "Crocodile",39 to "Tiger",40 to "Elephant")
        return Regex("""(?:u|unit)?(3[1-9]|40)[^0-9]{0,20}(\d+)""",RegexOption.IGNORE_CASE).findAll(s).map{names[it.groupValues[1].toInt()]+" "+it.groupValues[2]}.joinToString(", ")
    }
    private fun token(s:String,key:String):String {
        val m=Regex("""(?:$key)[^>]{0,100}>(.*?)</""",RegexOption.IGNORE_CASE).find(s) ?: return ""
        return android.text.Html.fromHtml(m.groupValues[1],android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    private fun coord(t:JSONObject,n:String):Int = t.optInt(n,t.optJSONObject("position")?.optInt(n,Int.MIN_VALUE) ?: Int.MIN_VALUE)

    private fun addFarmlist(oasis:Boolean) {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val name=farmList.text.toString().trim(); val tr=troop.text.toString().trim(); val count=troopCount.intValue()
                require(name.isNotBlank()){"Nama farmlist wajib diisi"}; require(tr.matches(Regex("t\\d+"))){ "Unit harus seperti t1, t2, dst."}; require(count>0){"Jumlah unit harus > 0"}
                val host=normalizeServer(); runner.load("https://$host/build.php?gid=16")
                val targetCoords=if(oasis) db.oasisAll().filter{!it.occupied}.map{it.x to it.y} else db.travcoAll().map{it.x to it.y}
                require(targetCoords.isNotEmpty()){"Database target kosong"}
                setStatus("memasukkan ${targetCoords.size} target ke farmlist")
                addTargetsToFarmList(name,tr,count,targetCoords)
                setStatus("farmlist selesai")
            } catch(e:Exception){log("FARMLIST ERROR: ${e.message}");setStatus("error")}
            finally{setBusy(false)}
        }
    }

    private suspend fun addTargetsToFarmList(listName:String,troopUnit:String,troopAmount:Int,coords:List<Pair<Int,Int>>) {
        // Open Rally Point / Farm List and locate the existing list by name.
        runner.load("https://${normalizeServer()}/build.php?gid=16")
        repeat(30){ if(runner.jsBoolean("!!document.querySelector('#rallyPointFarmList')")) return@repeat; kotlinx.coroutines.delay(300) }
        val lid=runner.jsString("""(() => {
            const norm=s=>(s||'').replace(/\s+/g,' ').trim().toLowerCase();
            for(const w of document.querySelectorAll('#rallyPointFarmList .farmListWrapper')){
              const n=norm(w.querySelector('.farmListName .name')?.textContent);
              if(n===norm(${JSONObject.quote(listName)})){
                return w.querySelector('.dragAndDrop[data-list]')?.getAttribute('data-list') ||
                       w.querySelector('[data-farm-list-id]')?.getAttribute('data-farm-list-id') || '';
              }
            } return '';
        })()""")
        require(lid.isNotBlank()){"Farmlist '$listName' tidak ditemukan. Buat farmlist terlebih dahulu di Travian."}
        var added=0
        for((x,y) in coords){
            val result=runner.jsString(ADD_TARGET_JS
                .replace("__LID__",JSONObject.quote(lid)).replace("__X__",x.toString()).replace("__Y__",y.toString())
                .replace("__UNIT__",JSONObject.quote(troopUnit)).replace("__COUNT__",troopAmount.toString()))
            when(result){
                "added"->{added++; if(added%10==0)log("FARMLIST: $added/${coords.size}")}
                "already"->{}
                else->{log("FARMLIST gagal ($x|$y): $result")}
            }
            kotlinx.coroutines.delay(Random.nextLong(250,650))
        }
        log("FARMLIST selesai: $added/${coords.size} ditambahkan")
    }

    private fun parseCoords(s:String):Pair<Int,Int>? {
        val m=Regex("""[?&](?:x|xcoord)=(-?\d+).*?[& ](?:y|ycoord)=(-?\d+)""").find(s)
            ?: Regex("""(-?\d+)\|(-?\d+)""").find(s)
        return m?.let{it.groupValues[1].toInt() to it.groupValues[2].toInt()}
    }
    private fun normalizeServer():String {
        var h=server.text.toString().trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        require(h.isNotBlank()){"Server wajib diisi"}
        return h
    }
    private fun loadPrefs(){
        val p=getSharedPreferences("cfg",0); server.setText(p.getString("server","")); xEdit.setText(p.getString("x","0")); yEdit.setText(p.getString("y","0"))
    }
    private fun savePrefs(){getSharedPreferences("cfg",0).edit().putString("server",server.text.toString()).putString("x",xEdit.text.toString()).putString("y",yEdit.text.toString()).apply()}
    private fun EditText.intValue()=text.toString().trim().toIntOrNull() ?: 0
    private fun scanCenters(minX:Int,maxX:Int,minY:Int,maxY:Int,r:Int):List<Pair<Int,Int>>{
        val step=r*2+1
        val xs=(minX..maxX step step).map{it+r}.filter{it<=maxX}
        val ys=(minY..maxY step step).map{it+r}.filter{it<=maxY}
        val out=mutableListOf<Pair<Int,Int>>()
        ys.forEachIndexed{row,y->(if(row%2==0)xs else xs.reversed()).forEach{x->out+=x to y}}
        return out
    }

    companion object {
        private const val TRAVCO_SCRAPE_JS = """(() => {
          const norm=s=>(s||'').replace(/\s+/g,' ').trim();
          const table=document.querySelector('main table'); if(!table) return JSON.stringify({rows:[],nextHref:''});
          const rows=[...table.querySelectorAll('tbody tr')].map(r=>{
            const c=[...r.querySelectorAll('td')];
            const a=c[3]?.querySelector('a.js-travian_village_url,a[href*="karte.php"]');
            const text=norm(a?.getAttribute('data-original-title')||a?.getAttribute('title')||a?.textContent);
            const coord=norm(a?.querySelector('.text-muted.small')?.textContent||'');
            const href=a?.href||'';
            const pop=norm(c[3]?.querySelector('[data-original-title="Population"],[title="Population"]')?.textContent||'');
            return {distance:parseFloat((c[1]?.textContent||'').replace(',','.'))||0,account:norm(c[2]?.querySelector('.detail-button')?.textContent),village:text,population:parseInt(pop)||0,coordinates:coord,href};
          });
          const current=parseInt(document.querySelector('main a.btn.active[href*="page="]')?.textContent||'1')||1;
          const links=[...document.querySelectorAll('main a[href*="page="]')].map(a=>({n:parseInt(a.textContent.trim())||0,h:a.href})).filter(x=>x.n>current).sort((a,b)=>a.n-b.n);
          return JSON.stringify({rows,nextHref:links[0]?.h||''});
        })()"""
        private const val OASIS_FETCH_JS = """(async()=>{try{
          const r=await fetch('/api/v1/map/position',{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},
          body:JSON.stringify({data:{x:__X__,y:__Y__,zoomLevel:3,ignorePositions:[]}})});
          return await r.text();
        }catch(e){return JSON.stringify({error:String(e)})}})()"""
        private const val ADD_TARGET_JS = """(async()=>{try{
          const root=document.querySelector('#farmListTargetForm')||document;
          const open=[...document.querySelectorAll('button,a')].find(e=>/add farm|add target|add farms/i.test((e.textContent||'')) && !e.disabled);
          if(open) open.click();
          await new Promise(r=>setTimeout(r,350));
          const f=document.querySelector('#farmListTargetForm'); if(!f) return 'dialog-not-found';
          const set=(sel,val)=>{const e=f.querySelector(sel);if(!e)return false;e.value=String(val);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true};
          const xs=set('input[name="x"],input[name="xCoord"],input[id*="xCoord" i]','__X__');
          const ys=set('input[name="y"],input[name="yCoord"],input[id*="yCoord" i]','__Y__');
          const ls=f.querySelector('select[name="listId"]'); if(ls){ls.value='__LID__';ls.dispatchEvent(new Event('change',{bubbles:true}))}
          if(!xs||!ys)return 'coord-input-not-found';
          const t= f.querySelector('input[name="__UNIT__"],input.unitAmount[name="__UNIT__"]'); if(t){t.value='__COUNT__';t.dispatchEvent(new Event('input',{bubbles:true}))}
          const target=f.querySelector('.targetSelection,.targetSelectionResultWrapper,.actionButtons')||f;
          target.dispatchEvent(new MouseEvent('click',{bubbles:true}));
          await new Promise(r=>setTimeout(r,700));
          const save=f.querySelector('button.save,button[type="submit"]'); if(!save)return 'save-not-found';
          if(save.disabled)return 'target-invalid';
          save.click();
          await new Promise(r=>setTimeout(r,600));
          const txt=(f.textContent||'').toLowerCase();
          if(/already.*selected|already.*list|already in/i.test(txt)) return 'already';
          return 'added';
        }catch(e){return 'error:'+e}})()"""
    }
}
