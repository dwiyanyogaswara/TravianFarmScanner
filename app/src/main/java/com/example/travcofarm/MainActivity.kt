package com.example.travcofarm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var serverInput: EditText
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var radiusInput: EditText
    private lateinit var farmListInput: EditText
    private lateinit var unitInput: EditText
    private lateinit var countInput: EditText
    private lateinit var logView: TextView
    private lateinit var webView: WebView
    private lateinit var travcoCount: TextView
    private lateinit var oasisCount: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val db by lazy { ScannerDb(this) }
    private var pageReady = false
    private var currentPage = ""
    private var oasisPending = 0
    private var oasisDone = 0
    private var oasisStartedAt = 0L

    private val prefs by lazy { getSharedPreferences("scanner", MODE_PRIVATE) }
    private val hardUser = "TNR#EMBUH"
    private val hardPass = "Gooner4life!"

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(45,45,45)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 28)
        }
        root.addView(content)

        serverInput = edit("Server Travian", prefs.getString("server", "https://rog.x3.europe.travian.com") ?: "")
        xInput = edit("X", "97")
        yInput = edit("Y", "-83")
        radiusInput = edit("Radius", "50")
        farmListInput = edit("Nama farmlist yang sudah ada", "")
        unitInput = edit("Unit, contoh t1", "t1")
        countInput = edit("Jumlah unit, contoh 20", "20")

        content.addView(serverInput)
        content.addView(xInput)
        content.addView(yInput)
        content.addView(radiusInput)

        val loginRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loginRow.addView(button("LOGIN TRAVIAN") { loginTravian() }, lp(1f))
        loginRow.addView(button("LOGOUT") { logoutTravian() }, lp(1f))
        content.addView(loginRow)

        content.addView(button("SCAN TRAVCO VILLAGE") { scanTravco() })
        travcoCount = label("Travco DB: ${db.travcoCount()}")
        content.addView(travcoCount)

        content.addView(button("SCAN OASIS MAP") { scanOasis() })
        oasisCount = label("Oasis DB: ${db.oasisCount()}")
        content.addView(oasisCount)

        content.addView(label("FARMLIST", 20f))
        content.addView(farmListInput)
        content.addView(unitInput)
        content.addView(countInput)
        content.addView(button("MASUKKAN FARMLIST DARI TRAVCO") { addTravcoToFarmList() })
        content.addView(button("MASUKKAN FARMLIST DARI OASIS") { addOasisToFarmList() })

        content.addView(label("LOG", 18f))
        logView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(8, 4, 8, 12)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        content.addView(logView)

        content.addView(label("WEBVIEW — DESKTOP MODE", 18f))
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(520))
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            overScrollMode = WebView.OVER_SCROLL_ALWAYS
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        configureWebView()
        content.addView(webView)

        setContentView(root)
        log("APP START")
        log("Hardcoded Travian account enabled")
        log("WebView mode=DESKTOP, scroll=ON")
        log("Travco DB=${db.travcoCount()}, Oasis DB=${db.oasisCount()}")
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pageReady = true
                currentPage = url
                log("WEBVIEW PAGE READY: $url")
                if (url.contains("travian.com")) {
                    handler.postDelayed({ autoFillLoginIfNeeded() }, 900)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                log("NAV: ${request.url}")
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) log("WEBVIEW ERROR: ${error.errorCode} ${error.description} URL=${request.url}")
                super.onReceivedError(view, request, error)
            }
        }
    }

    private fun loginTravian() {
        val server = normalizeServer(serverInput.text.toString())
        prefs.edit().putString("server", server).apply()
        pageReady = false
        log("LOGIN START server=$server")
        log("LOGIN credentials: hardcoded user is present; password is not printed to log")
        webView.loadUrl(server)
    }

    private fun autoFillLoginIfNeeded() {
        val js = """
            (function(){
              const inputs=[...document.querySelectorAll('input')];
              const user=inputs.find(i=>/user|name|login|email/i.test(i.name+' '+i.id+' '+i.autocomplete) && i.type!=='password');
              const pass=inputs.find(i=>i.type==='password' || /pass/i.test(i.name+' '+i.id));
              const submit=document.querySelector('button[type=submit],input[type=submit],button.login,button[name*=login i]');
              if(!user || !pass) return JSON.stringify({ok:false,reason:'login fields not found',url:location.href,inputs:inputs.length});
              user.value=${JSONObject.quote(hardUser)};
              pass.value=${JSONObject.quote(hardPass)};
              for(const el of [user,pass]) el.dispatchEvent(new Event('input',{bubbles:true}));
              if(submit){ submit.click(); return JSON.stringify({ok:true,action:'submit',url:location.href}); }
              const form=user.form || pass.form;
              if(form){ form.submit(); return JSON.stringify({ok:true,action:'form.submit',url:location.href}); }
              return JSON.stringify({ok:true,action:'filled-only',url:location.href});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result -> log("LOGIN JS RESULT: ${cleanJsResult(result)}") }
    }

    private fun logoutTravian() {
        log("LOGOUT START page=$currentPage")
        webView.evaluateJavascript("""
          (function(){
            const links=[...document.querySelectorAll('a,button')];
            const el=links.find(e=>/logout|log out|keluar/i.test((e.innerText||'')+' '+(e.getAttribute('href')||'')+' '+(e.id||'')));
            if(!el) return JSON.stringify({ok:false,reason:'logout DOM element not found',url:location.href});
            const href=el.href||el.getAttribute('href')||''; el.click();
            return JSON.stringify({ok:true,tag:el.tagName,id:el.id,href:href,text:(el.innerText||'').trim().slice(0,80)});
          })();
        """) { result ->
            log("LOGOUT DOM RESULT: ${cleanJsResult(result)}")
            handler.postDelayed({
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    webView.clearCache(true)
                    webView.clearHistory()
                    log("LOGOUT COOKIE CLEAR: done")
                }
            }, 1200)
        }
    }

    private fun scanTravco() {
        val serverHost = Uri.parse(normalizeServer(serverInput.text.toString())).host ?: ""
        val x = xInput.text.toString().trim()
        val y = yInput.text.toString().trim()
        log("TRAVCO SCAN START host=$serverHost center=($x|$y)")
        if (serverHost.isBlank()) { log("TRAVCO ERROR: invalid server"); return }
        currentPage = "https://travcotools.com/en/inactive-search/"
        webView.loadUrl(currentPage)
        handler.postDelayed({ runTravcoSearch(serverHost, x, y) }, 1800)
    }

    private fun runTravcoSearch(serverHost: String, x: String, y: String) {
        val js = """
          (function(){
            const setVal=(sel,val)=>{const e=document.querySelector(sel);if(!e)return false;e.value=val;e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new Event('input',{bubbles:true}));return true};
            const opts=[...document.querySelectorAll('#id_travian_server option')];
            const opt=opts.find(o=>(o.textContent||'').trim().toLowerCase()==='${jsEscape(serverHost.lowercase())}');
            const report={url:location.href,serverFound:!!opt,form:!!document.querySelector('#id_travian_server'),x:setVal('#id_x','${jsEscape(x)}'),y:setVal('#id_y','${jsEscape(y)}'),days:setVal('#id_days','7'),order:setVal('#id_order_by','population'),pageSize:setVal('#id_page_size','100')};
            if(opt){document.querySelector('#id_travian_server').value=opt.value;document.querySelector('#id_travian_server').dispatchEvent(new Event('change',{bubbles:true}));}
            const submit=document.querySelector("button.btn.btn-light.primary[type='submit']")||document.querySelector("button[type='submit'],input[type='submit']");
            report.submit=!!submit;
            if(submit) submit.click();
            return JSON.stringify(report);
          })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            log("TRAVCO FORM RESULT: ${cleanJsResult(result)}")
            handler.postDelayed({ scrapeTravcoDom() }, 3500)
        }
    }

    private fun scrapeTravcoDom() {
        val js = """
          (function(){
            const table=document.querySelector('main table');
            if(!table) return JSON.stringify({ok:false,reason:'result table not found',url:location.href,title:document.title,body:(document.body?.innerText||'').slice(0,1200)});
            const rows=[...table.querySelectorAll('tbody tr')].map(row=>{
              const c=[...row.querySelectorAll('td')];
              const link=c[3]?.querySelector('a.js-travian_village_url,a[href*="karte.php"]');
              const text=(v)=>(v||'').trim();
              const href=link?.href||'';
              const m=href.match(/[?&]x=(-?\\d+).*?[?&]y=(-?\\d+)/i);
              const coord=m?m[1]+'|'+m[2]:text(link?.querySelector('.text-muted.small')?.textContent);
              return {distance:text(c[1]?.textContent),account:text(c[2]?.querySelector('.detail-button')?.textContent),village:text(link?.getAttribute('data-original-title')||link?.getAttribute('title')||link?.textContent),population:text(c[3]?.querySelector('[data-original-title="Population"],[title="Population"]')?.textContent),coord:coord};
            });
            return JSON.stringify({ok:true,url:location.href,count:rows.length,rows:rows});
          })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val raw = unquoteJs(result)
            try {
                val obj = JSONObject(raw)
                log("TRAVCO DOM RESULT: ok=${obj.optBoolean("ok")} url=${obj.optString("url")} count=${obj.optInt("count")}")
                if (!obj.optBoolean("ok")) { log("TRAVCO DETAIL: ${obj.optString("reason")} BODY=${obj.optString("body")}"); return@evaluateJavascript }
                val rows = obj.optJSONArray("rows") ?: JSONArray()
                var saved=0
                for(i in 0 until rows.length()) {
                    val r=rows.optJSONObject(i) ?: continue
                    val coord=r.optString("coord")
                    val parts=coord.split("|")
                    if(parts.size!=2) { log("TRAVCO ROW $i: skipped, no coordinate") ; continue }
                    db.insertTravco(parts[0].toIntOrNull() ?: continue, parts[1].toIntOrNull() ?: continue, r.optString("account"), r.optString("village"), r.optDouble("distance",0.0), r.optLong("population",0))
                    saved++
                }
                travcoCount.text="Travco DB: ${db.travcoCount()}"
                log("TRAVCO SCAN END saved=$saved totalDB=${db.travcoCount()}")
            } catch(e:Exception) { log("TRAVCO PARSE ERROR: ${e.message}; RAW=${raw.take(1500)}") }
        }
    }

    private fun scanOasis() {
        val server = normalizeServer(serverInput.text.toString())
        val cx=xInput.text.toString().toIntOrNull(); val cy=yInput.text.toString().toIntOrNull(); val radius=radiusInput.text.toString().toIntOrNull()
        if(cx==null || cy==null || radius==null || radius<0) { log("OASIS ERROR: X/Y/radius invalid"); return }
        log("OASIS SCAN START server=$server center=($cx|$cy) radius=$radius")
        pageReady=false
        webView.loadUrl("$server/karte.php")
        handler.postDelayed({ startOasisRequests(cx,cy,radius) }, 2500)
    }

    private fun startOasisRequests(cx:Int, cy:Int, radius:Int) {
        val step=30
        val startX=cx-radius; val endX=cx+radius; val startY=cy-radius; val endY=cy+radius
        val xs=(startX..endX step step).toMutableList().apply { if(lastOrNull()!=endX) add(endX) }
        val ys=(startY..endY step step).toMutableList().apply { if(lastOrNull()!=endY) add(endY) }
        oasisPending=xs.size*ys.size; oasisDone=0; oasisStartedAt=System.currentTimeMillis()
        log("OASIS GRID: ${xs.size}x${ys.size}=$oasisPending API requests, step=$step, zoomLevel=3")
        for(y in ys) for(x in xs) {
            val js="""
              (async function(){
                const u=location.origin+'/api/v1/map/position';
                try{
                  const r=await fetch(u,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify({data:{x:$x,y:$y,zoomLevel:3,ignorePositions:[]}})});
                  const t=await r.text();
                  AndroidBridge.onOasisResponse(JSON.stringify({x:$x,y:$y,status:r.status,ok:r.ok,url:u,contentType:r.headers.get('content-type')||'',length:t.length,body:t.slice(0,800000)}));
                }catch(e){AndroidBridge.onOasisResponse(JSON.stringify({x:$x,y:$y,status:0,ok:false,url:u,error:String(e)}));}
              })();
            """.trimIndent()
            webView.evaluateJavascript(js,null)
        }
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun onOasisResponse(payload:String) {
            runOnUiThread {
                oasisDone++
                try {
                    val o=JSONObject(payload)
                    val status=o.optInt("status")
                    val len=o.optInt("length")
                    val x=o.optInt("x"); val y=o.optInt("y")
                    log("OASIS API [$oasisDone/$oasisPending] center=($x|$y) HTTP=$status ok=${o.optBoolean("ok")} bytes=$len type=${o.optString("contentType")}")
                    if(!o.optBoolean("ok")) { log("OASIS API ERROR: ${o.optString("error")} BODY=${o.optString("body").take(500)}") }
                    else if(len==0) { log("OASIS API ERROR: HTTP body EMPTY at center=($x|$y)") }
                    else parseOasisJson(o.optString("body"), x,y)
                } catch(e:Exception) { log("OASIS BRIDGE PARSE ERROR: ${e.message}; payload=${payload.take(1200)}") }
                if(oasisDone>=oasisPending) {
                    oasisCount.text="Oasis DB: ${db.oasisCount()}"
                    log("OASIS SCAN END saved=${db.oasisCount()} elapsed=${System.currentTimeMillis()-oasisStartedAt}ms")
                }
            }
        }
    }

    private fun parseOasisJson(json:String, requestX:Int, requestY:Int) {
        try {
            val root=JSONObject(json); val tiles=root.optJSONArray("tiles")
            if(tiles==null) { log("OASIS PARSE: no tiles[] at center=($requestX|$requestY), root=${json.take(300)}"); return }
            var found=0
            for(i in 0 until tiles.length()) {
                val t=tiles.optJSONObject(i) ?: continue
                val did=t.optInt("did", Int.MIN_VALUE); if(did!=-1) continue
                val title=t.optString("title"); if(title!="{k.fo}" && title!="{k.bt}") continue
                val text=stripFormat(t.optString("text")); val type=mapOasisType(text) ?: continue
                val x=readCoord(t,"x"); val y=readCoord(t,"y"); if(x==null||y==null) continue
                val occupied=title=="{k.bt}" || t.has("uid")
                val animals=if(occupied) "" else parseAnimals(text)
                val owner=if(occupied) extract(text,"\\{k\\.spieler\\}\\s*(.*?)\\s*(?:<br\\s*/?>|\\{k\\.|$)") else ""
                val alliance=if(occupied) extract(text,"\\{k\\.allianz\\}\\s*(.*?)\\s*(?:<br\\s*/?>|\\{k\\.|$)") else ""
                db.insertOasis(x,y,occupied,type.first,type.second,animals,owner,alliance)
                found++
            }
            if(found>0) log("OASIS PARSE: center=($requestX|$requestY) tiles=${tiles.length()} oasisSaved=$found")
        } catch(e:Exception) { log("OASIS JSON ERROR center=($requestX|$requestY): ${e.message}; JSON=${json.take(1200)}") }
    }

    private fun addTravcoToFarmList() { addFromDb(false) }
    private fun addOasisToFarmList() { addFromDb(true) }

    private fun addFromDb(oasis:Boolean) {
        val list=farmListInput.text.toString().trim(); val unit=unitInput.text.toString().trim(); val count=countInput.text.toString().toIntOrNull() ?: 0
        if(list.isBlank() || unit.isBlank() || count<=0) { log("FARMLIST ERROR: list/unit/count invalid"); return }
        val rows=if(oasis) db.oasisCoords() else db.travcoCoords()
        log("FARMLIST START source=${if(oasis) "OASIS" else "TRAVCO"} list='$list' targets=${rows.size} unit=$unit count=$count")
        if(rows.isEmpty()) { log("FARMLIST ERROR: database source empty"); return }
        val server=normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
        handler.postDelayed({
            addFarmTargetsSequentially(list,unit,count,rows,0)
        },3000)
    }

    private fun addFarmTargetsSequentially(list:String,unit:String,count:Int,coords:List<Pair<Int,Int>>,index:Int) {
        if(index>=coords.size) { log("FARMLIST END completed=${coords.size}"); return }
        val (x,y)=coords[index]
        val js="""
          (async function(){
            const sleep=ms=>new Promise(r=>setTimeout(r,ms));
            const clean=v=>(v||'').replace(/\\s+/g,' ').trim();
            const norm=v=>clean(v).replace(/\\(\\d+\\s*farms?\\)/ig,'').replace(/\\bdelete\\b/ig,'').trim().toLowerCase();
            const targetName=${JSONObject.quote(list)};
            const troop=${JSONObject.quote(unit)};
            const amount=${count};
            const result={x:${x},y:${y},url:location.href,steps:[]};
            try{
              if(!location.href.includes('gid=16')) result.steps.push('page_not_farm_list');
              const wrappers=[...document.querySelectorAll('#rallyPointFarmList .farmListWrapper')];
              const wrapper=wrappers.find(w=>norm(w.querySelector('.farmListName .name')?.textContent)===norm(targetName));
              if(!wrapper){result.error='Farm List not found';return JSON.stringify(result);}
              const lid=wrapper.querySelector('.dragAndDrop[data-list]')?.getAttribute('data-list')||'';
              result.lid=lid;
              const add=wrapper.querySelector('td.addTarget a, td.addTarget button');
              if(!add){result.error='Add Target button not found';return JSON.stringify(result);}
              add.click(); result.steps.push('open_add_target');
              let form=null;
              for(let i=0;i<40;i++){form=document.querySelector('#farmListTargetForm');if(form)break;await sleep(250);}
              if(!form){result.error='farmListTargetForm did not render';return JSON.stringify(result);}
              const xi=form.querySelector('input[name="x"],input[name="xCoord"],input[id*="xCoord" i]')||[...form.querySelectorAll('input')].find(e=>/^(x|xcoord|coordx)$/i.test(e.name||e.id));
              const yi=form.querySelector('input[name="y"],input[name="yCoord"],input[id*="yCoord" i]')||[...form.querySelectorAll('input')].find(e=>/^(y|ycoord|coordy)$/i.test(e.name||e.id));
              if(!xi||!yi){result.error='X/Y input not found';return JSON.stringify(result);}
              const set=(e,v)=>{e.focus();e.value=String(v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Enter'}));};
              set(xi,${x});set(yi,${y});result.steps.push('coordinates_filled');
              const trigger=form.querySelector('.targetSelection,.targetSelectionResultWrapper,.troopSelection')||form;
              trigger.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));trigger.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));trigger.dispatchEvent(new MouseEvent('click',{bubbles:true}));
              let save=null;
              for(let i=0;i<50;i++){
                save=form.querySelector('button.save,button[type="submit"]');
                const err=form.querySelector('.targetSelectionResultWrapper.hasError .targetSelectionValidation.show,.targetSelectionResultWrapper.hasError .customValidationRenderElement');
                if(save && !save.disabled) break;
                if(err && clean(err.textContent)){result.error='target validation: '+clean(err.textContent).slice(0,180);return JSON.stringify(result);}
                await sleep(200);
              }
              if(!save || save.disabled){result.error='Save button stayed disabled';return JSON.stringify(result);}
              const troopInput=form.querySelector('input[name="'+troop+'"],input.unitAmount[name="'+troop+'"]');
              if(troopInput){set(troopInput,amount);result.steps.push('troop_filled');}
              else result.steps.push('troop_input_not_found');
              await sleep(250);
              save.click();result.steps.push('save_clicked');
              for(let i=0;i<40;i++){if(!document.querySelector('#farmListTargetForm')){result.ok=true;return JSON.stringify(result);}await sleep(250);}
              result.error='Save clicked but form remained open';
              return JSON.stringify(result);
            }catch(e){result.error=String(e&&e.message||e);return JSON.stringify(result);}
          })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val raw=unquoteJs(result)
            try {
                val o=JSONObject(raw)
                log("FARMLIST TARGET ${index+1}/${coords.size} (${x}|${y}) RESULT: ok=${o.optBoolean("ok")} lid=${o.optString("lid")} steps=${o.optJSONArray("steps")?.toString() ?: "[]"}")
                if(o.has("error")) log("FARMLIST TARGET ERROR (${x}|${y}): ${o.optString("error")}")
            } catch(e:Exception) { log("FARMLIST RESULT PARSE ERROR: ${e.message}; RAW=${raw.take(1500)}") }
            if(index+1<coords.size) handler.postDelayed({ addFarmTargetsSequentially(list,unit,count,coords,index+1) },450)
            else log("FARMLIST END processed=${coords.size}")
        }
    }

    private fun mapOasisType(text:String):Pair<String,String>? {
        val bonuses=Regex("\\{a:r([1-4])\\}[^%]{0,40}(25|50)%",RegexOption.IGNORE_CASE).findAll(text).map{it.groupValues[1].toInt() to it.groupValues[2].toInt()}.distinct().sortedWith(compareBy({it.first},{it.second})).toList()
        return when(bonuses){
            listOf(1 to 25)->"Wood 25%" to "Wood"
            listOf(1 to 50)->"Wood 50%" to "Wood"
            listOf(2 to 25)->"Clay 25%" to "Clay"
            listOf(2 to 50)->"Clay 50%" to "Clay"
            listOf(3 to 25)->"Iron 25%" to "Iron"
            listOf(3 to 50)->"Iron 50%" to "Iron"
            listOf(4 to 25)->"Crop 25%" to "Crop"
            listOf(4 to 50)->"Crop 50%" to "Crop"
            listOf(1 to 25,4 to 25)->"Wood+Crop" to "Wood+Crop"
            listOf(2 to 25,4 to 25)->"Clay+Crop" to "Clay+Crop"
            listOf(3 to 25,4 to 25)->"Iron+Crop" to "Iron+Crop"
            else->null
        }
    }
    private fun parseAnimals(text:String):String=Regex("unit\\s+u(\\d+)\\\"\\s*>\\s*</i>\\s*<span\\s+class=\\\"value\\s*\\\">\\s*(\\d+)",RegexOption.IGNORE_CASE).findAll(text).joinToString(", "){animalName(it.groupValues[1].toInt())+" "+it.groupValues[2]}
    private fun animalName(id:Int)=when(id){31->"Rat";32->"Spider";33->"Snake";34->"Bat";35->"Wild Boar";36->"Wolf";37->"Bear";38->"Crocodile";39->"Tiger";40->"Elephant";else->"u$id"}
    private fun extract(text:String,pattern:String)=Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"),"")?.trim().orEmpty()
    private fun stripFormat(s:String)=s.filter{it.code !in 0x200B..0x206F}
    private fun readCoord(t:JSONObject,n:String):Int?=if(t.has(n)) t.optString(n).toIntOrNull() ?: t.optInt(n).takeIf{t.opt(n) is Number} else t.optJSONObject("position")?.optString(n)?.toIntOrNull() ?: t.optJSONObject("coordinates")?.optString(n)?.toIntOrNull()

    private fun normalizeServer(value:String):String { var s=value.trim(); if(!s.startsWith("http")) s="https://$s"; return s.trimEnd('/') }
    private fun jsEscape(s:String)=s.replace("\\","\\\\").replace("'","\\'")
    private fun unquoteJs(s:String):String=try{ JSONTokener(s).nextValue()?.toString() ?: "" }catch(_:Exception){s.trim('"').replace("\\\"", "\"").replace("\\n", "\n")}
    private fun cleanJsResult(s:String)=unquoteJs(s).replace("\\u003C","<").replace("\\u003E",">").take(1800)

    private fun log(message:String) { val line="${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())} | $message\n"; logView.append(line) }
    private fun edit(hint:String,value:String)=EditText(this).apply{setHint(hint);setText(value);setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);textSize=18f}
    private fun label(text:String,size:Float=16f)=TextView(this).apply{this.text=text;setTextColor(Color.LTGRAY);textSize=size;setPadding(0,10,0,8)}
    private fun button(text:String,onClick:()->Unit)=Button(this).apply{this.text=text;setOnClickListener{onClick()};isAllCaps=false}
    private fun lp(weight:Float)=LinearLayout.LayoutParams(0,-2,weight).apply{setMargins(4,4,4,4)}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()

    override fun onDestroy(){ webView.removeJavascriptInterface("AndroidBridge"); webView.destroy(); db.close(); super.onDestroy() }

    private class ScannerDb(ctx:Context):android.database.sqlite.SQLiteOpenHelper(ctx,"scanner.db",null,1){
        override fun onCreate(db:android.database.sqlite.SQLiteDatabase){
            db.execSQL("CREATE TABLE travco(id INTEGER PRIMARY KEY AUTOINCREMENT,x INTEGER,y INTEGER,account TEXT,village TEXT,distance REAL,population INTEGER,UNIQUE(x,y))")
            db.execSQL("CREATE TABLE oasis(id INTEGER PRIMARY KEY AUTOINCREMENT,x INTEGER,y INTEGER,occupied INTEGER,oasisType TEXT,filterType TEXT,animals TEXT,owner TEXT,alliance TEXT,UNIQUE(x,y))")
        }
        override fun onUpgrade(db:android.database.sqlite.SQLiteDatabase,oldVersion:Int,newVersion:Int){}
        fun insertTravco(x:Int,y:Int,a:String,v:String,d:Double,p:Long){writableDatabase.execSQL("INSERT OR REPLACE INTO travco(x,y,account,village,distance,population) VALUES(?,?,?,?,?,?)",arrayOf(x,y,a,v,d,p))}
        fun insertOasis(x:Int,y:Int,o:Boolean,t:String,f:String,a:String,owner:String,alliance:String){writableDatabase.execSQL("INSERT OR REPLACE INTO oasis(x,y,occupied,oasisType,filterType,animals,owner,alliance) VALUES(?,?,?,?,?,?,?,?)",arrayOf(x,y,if(o)1 else 0,t,f,a,owner,alliance))}
        fun travcoCount()=readableDatabase.rawQuery("SELECT COUNT(*) FROM travco",null).use{it.moveToFirst();it.getInt(0)}
        fun oasisCount()=readableDatabase.rawQuery("SELECT COUNT(*) FROM oasis",null).use{it.moveToFirst();it.getInt(0)}
        fun travcoCoords(): List<Pair<Int, Int>> = coords("SELECT x,y FROM travco ORDER BY distance ASC")
        fun oasisCoords(): List<Pair<Int, Int>> = coords("SELECT x,y FROM oasis WHERE occupied=0 ORDER BY id")
        private fun coords(sql:String):List<Pair<Int,Int>>{val r=ArrayList<Pair<Int,Int>>();readableDatabase.rawQuery(sql,null).use{while(it.moveToNext())r.add(it.getInt(0) to it.getInt(1))};return r}
    }
}
