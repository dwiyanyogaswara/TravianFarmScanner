package com.example.travcofarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
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
    private lateinit var farmListSpinner: Spinner
    private val farmLists = mutableListOf<String>()
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
        farmListSpinner = Spinner(this)
        farmListSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, farmLists)

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

        val dbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dbRow.addView(button("DB OVERVIEW") { showDbOverview() }, lp(1f))
        dbRow.addView(button("COPY LOG") { copyLog() }, lp(1f))
        content.addView(dbRow)

        content.addView(label("FARMLIST", 20f))
        content.addView(farmListSpinner)
        content.addView(button("REFRESH FARMLIST AKUN") { loadFarmLists() })
        content.addView(button("MASUKKAN FARMLIST DARI TRAVCO") { addTravcoToFarmList() })
        content.addView(button("MASUKKAN FARMLIST DARI OASIS") { addOasisToFarmList() })
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

        content.addView(label("LOG", 18f))
        logView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(8, 4, 8, 12)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
        }
        content.addView(logView)

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
        val cx = xInput.text.toString().toIntOrNull()
        val cy = yInput.text.toString().toIntOrNull()
        val radius = radiusInput.text.toString().toIntOrNull()
        if (cx == null || cy == null || radius == null || radius < 0) {
            log("OASIS ERROR: X/Y/radius invalid x='${xInput.text}' y='${yInput.text}' radius='${radiusInput.text}'")
            return
        }
        log("OASIS SCAN START server=$server center=($cx|$cy) radius=$radius")
        log("OASIS DB BEFORE: total=${db.oasisCount()} unoccupied=${db.oasisUnoccupiedCount()} occupied=${db.oasisOccupiedCount()}")
        pageReady = false
        webView.loadUrl("$server/karte.php")
        handler.postDelayed({ verifyOasisPageAndStart(cx, cy, radius) }, 3000)
    }

    private fun verifyOasisPageAndStart(cx: Int, cy: Int, radius: Int) {
        webView.evaluateJavascript("""
            (function(){
              return JSON.stringify({
                url:location.href,
                title:document.title,
                ready:document.readyState,
                bodyChars:(document.body?.innerText||'').length,
                hasTravianMap:!!document.querySelector('#map, .map, #mapContainer'),
                cookieEnabled:navigator.cookieEnabled
              });
            })();
        """.trimIndent()) { result ->
            val raw = unquoteJs(result)
            log("OASIS PAGE CHECK: ${raw.take(1000)}")
            if (!currentPage.contains("karte.php", ignoreCase = true) && !raw.contains("karte.php", ignoreCase = true)) {
                log("OASIS PAGE WARNING: current page is not karte.php; API may use wrong origin")
            }
            startOasisRequests(cx, cy, radius)
        }
    }

    private fun startOasisRequests(cx: Int, cy: Int, radius: Int) {
        val step = 30
        val startX = cx - radius
        val endX = cx + radius
        val startY = cy - radius
        val endY = cy + radius
        val xs = (startX..endX step step).toMutableList().apply { if (lastOrNull() != endX) add(endX) }
        val ys = (startY..endY step step).toMutableList().apply { if (lastOrNull() != endY) add(endY) }
        oasisPending = xs.size * ys.size
        oasisDone = 0
        oasisStartedAt = System.currentTimeMillis()
        log("OASIS GRID: ${xs.size}x${ys.size}=$oasisPending requests, step=$step, centersX=${xs.joinToString()}, centersY=${ys.joinToString()}")
        var seq = 0
        for (y in ys) for (x in xs) {
            seq++
            val requestNo = seq
            val js = """
              (async function(){
                const u=location.origin+'/api/v1/map/position';
                const payload={data:{x:$x,y:$y,zoomLevel:3,ignorePositions:[]}};
                const out={requestNo:$requestNo,x:$x,y:$y,url:u,payload:payload};
                try{
                  const r=await fetch(u,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json','accept':'application/json, text/plain, */*'},body:JSON.stringify(payload)});
                  const t=await r.text();
                  out.status=r.status;out.ok=r.ok;out.contentType=r.headers.get('content-type')||'';out.length=t.length;out.body=t;
                }catch(e){out.status=0;out.ok=false;out.error=String(e&&e.stack||e);}
                AndroidBridge.onOasisResponse(JSON.stringify(out));
              })();
            """.trimIndent()
            log("OASIS REQUEST [$requestNo/$oasisPending]: center=($x|$y)")
            webView.evaluateJavascript(js) { evalResult ->
                val cleaned = cleanJsResult(evalResult)
                if (cleaned.isNotBlank() && cleaned != "null") log("OASIS REQUEST JS RETURN [$requestNo]: ${cleaned.take(300)}")
            }
        }
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun onOasisResponse(payload: String) {
            runOnUiThread {
                oasisDone++
                try {
                    val o = JSONObject(payload)
                    val status = o.optInt("status")
                    val len = o.optInt("length")
                    val x = o.optInt("x")
                    val y = o.optInt("y")
                    val body = o.optString("body")
                    log("OASIS RESPONSE [$oasisDone/$oasisPending] center=($x|$y) HTTP=$status ok=${o.optBoolean("ok")} bytes=$len type=${o.optString("contentType")}")
                    if (o.has("error")) {
                        log("OASIS NETWORK ERROR center=($x|$y): ${o.optString("error").take(1000)}")
                    } else if (len == 0 || body.isBlank()) {
                        log("OASIS RESPONSE EMPTY center=($x|$y) HTTP=$status")
                    } else {
                        parseOasisJson(body, x, y)
                    }
                } catch (e: Exception) {
                    log("OASIS BRIDGE ERROR: ${e.message}; PAYLOAD=${payload.take(1800)}")
                }
                if (oasisDone >= oasisPending) {
                    oasisCount.text = "Oasis DB: ${db.oasisCount()}"
                    log("OASIS DB AFTER: total=${db.oasisCount()} unoccupied=${db.oasisUnoccupiedCount()} occupied=${db.oasisOccupiedCount()}")
                    log("OASIS SCAN END saved=${db.oasisCount()} elapsed=${System.currentTimeMillis() - oasisStartedAt}ms")
                }
            }
        }
    }

    private fun parseOasisJson(json: String, requestX: Int, requestY: Int) {
        try {
            val root = JSONObject(json)
            val keys = root.keys().asSequence().toList()
            val tiles = root.optJSONArray("tiles")
            if (tiles == null) {
                log("OASIS PARSE ERROR center=($requestX|$requestY): no tiles[]; rootKeys=${keys.joinToString()}; rootPreview=${json.take(1200)}")
                return
            }
            var didMinusOne = 0
            var titleOasis = 0
            var bonusCandidate = 0
            var coordCandidate = 0
            var saved = 0
            var sampleLogged = false
            for (i in 0 until tiles.length()) {
                val t = tiles.optJSONObject(i) ?: continue
                val did = readIntFlexible(t, "did")
                val title = t.optString("title")
                val text = stripFormat(t.optString("text"))
                if (did == -1) didMinusOne++
                if (title == "{k.fo}" || title == "{k.bt}") titleOasis++
                if (bonusRegex().containsMatchIn(text)) bonusCandidate++
                val x = readCoord(t, "x")
                val y = readCoord(t, "y")
                if (x != null && y != null) coordCandidate++
                if (!sampleLogged && (title == "{k.fo}" || title == "{k.bt}" || i == 0)) {
                    log("OASIS TILE SAMPLE center=($requestX|$requestY) index=$i did=$did title='$title' x=$x y=$y text='${text.take(500)}'")
                    sampleLogged = true
                }
                if (did != -1) continue
                if (title != "{k.fo}" && title != "{k.bt}") continue
                val type = mapOasisType(text)
                if (type == null) continue
                if (x == null || y == null) continue
                val occupied = title == "{k.bt}" || (t.has("uid") && t.opt("uid") is Number)
                val animals = if (occupied) "" else parseAnimals(text)
                val owner = if (occupied) extract(text, "\\{k\\.spieler\\}\\s*(.*?)\\s*(?:<br\\s*/?>|\\{k\\.|$)") else ""
                val alliance = if (occupied) extract(text, "\\{k\\.allianz\\}\\s*(.*?)\\s*(?:<br\\s*/?>|\\{k\\.|$)") else ""
                db.insertOasis(x, y, occupied, type.first, type.second, animals, owner, alliance)
                saved++
                if (saved <= 10) log("OASIS SAVE [$saved]: ($x|$y) type=${type.first} occupied=$occupied animals='${animals.take(120)}'")
            }
            log("OASIS PARSE center=($requestX|$requestY): tiles=${tiles.length()} did=-1:$didMinusOne titleOasis:$titleOasis bonus:$bonusCandidate coords:$coordCandidate saved:$saved")
        } catch (e: Exception) {
            log("OASIS JSON ERROR center=($requestX|$requestY): ${e.message}; JSON=${json.take(2000)}")
        }
    }

    private fun copyLog() {
        val text = logView.text?.toString().orEmpty()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Travian Farm Scanner Log", text))
        log("LOG COPIED: ${text.length} chars")
    }

    private fun showDbOverview() {
        val travco = db.travcoOverview(80)
        val oasis = db.oasisOverview(120)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        body.addView(TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 15f
            text = "TRAVCO: ${db.travcoCount()} rows\nOASIS: ${db.oasisCount()} rows\n\n"
        })
        body.addView(TextView(this).apply {
            setTextColor(Color.DKGRAY); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "TRAVCO DB (max 80)"
        })
        body.addView(TextView(this).apply {
            setTextColor(Color.DKGRAY); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            text = if (travco.isBlank()) "(empty)" else travco
            setTextIsSelectable(true)
        })
        body.addView(TextView(this).apply {
            setTextColor(Color.DKGRAY); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "\nOASIS DB (max 120)"
        })
        body.addView(TextView(this).apply {
            setTextColor(Color.DKGRAY); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            text = if (oasis.isBlank()) "(empty)" else oasis
            setTextIsSelectable(true)
        })
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("DATABASE OVERVIEW")
            .setView(scroll)
            .setPositiveButton("CLOSE", null)
            .show()
        log("DB OVERVIEW: travco=${db.travcoCount()} oasis=${db.oasisCount()} freeOasis=${db.oasisUnoccupiedCount()} occupiedOasis=${db.oasisOccupiedCount()}")
    }

    private fun selectedFarmList(): String {
        return if (farmLists.isNotEmpty() && farmListSpinner.selectedItemPosition >= 0)
            farmLists[farmListSpinner.selectedItemPosition]
        else ""
    }

    private fun loadFarmLists() {
        val server = normalizeServer(serverInput.text.toString())
        log("FARMLIST LOAD START")
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
        handler.postDelayed({
            val js = """
                (function(){
                  const clean=v=>(v||'').replace(/\\s+/g,' ').trim();
                  const norm=v=>clean(v).replace(/\\(\\d+\\s*farms?\\)/ig,'').trim();
                  const wrappers=[...document.querySelectorAll('#rallyPointFarmList .farmListWrapper')];
                  const lists=wrappers.map(w=>norm(w.querySelector('.farmListName .name')?.textContent||w.querySelector('.farmListName')?.textContent||'')).filter(Boolean);
                  return JSON.stringify({url:location.href,count:lists.length,lists:lists});
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { raw ->
                try {
                    val o=JSONObject(unquoteJs(raw))
                    val arr=o.optJSONArray("lists") ?: JSONArray()
                    farmLists.clear()
                    for(i in 0 until arr.length()) farmLists.add(arr.optString(i))
                    @Suppress("UNCHECKED_CAST")
                    (farmListSpinner.adapter as? ArrayAdapter<String>)?.notifyDataSetChanged()
                    if(farmLists.isEmpty()) log("FARMLIST LOAD ERROR: tidak ada farmlist ditemukan url=${o.optString("url")}")
                    else log("FARMLIST LOAD OK: ${farmLists.size} list = ${farmLists.joinToString()}")
                } catch(e:Exception) {
                    log("FARMLIST LOAD PARSE ERROR: ${e.message}; RAW=${cleanJsResult(raw)}")
                }
            }
        }, 1800)
    }

    private fun addTravcoToFarmList() { addFromDb(false) }
    private fun addOasisToFarmList() { addFromDb(true) }

    private fun addFromDb(oasis:Boolean) {
        val list=selectedFarmList()
        val rows=if(oasis) db.oasisCoords() else db.travcoCoords()
        log("FARMLIST START source=${if(oasis) "OASIS" else "TRAVCO"} list='$list' targets=${rows.size} troops=DEFAULT_FROM_ACCOUNT_FARMLIST")
        if(list.isBlank()) { log("FARMLIST ERROR: pilih Farmlist dari dropdown"); return }
        if(rows.isEmpty()) { log("FARMLIST ERROR: database source empty"); return }
        val server=normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
        handler.postDelayed({ addFarmTargetsSequentially(list,rows,0) },3000)
    }

    private fun addFarmTargetsSequentially(list:String,coords:List<Pair<Int,Int>>,index:Int) {
        if(index>=coords.size) { log("FARMLIST END completed=${coords.size}"); return }
        val (x,y)=coords[index]
        val js="""
          (async function(){
            const sleep=ms=>new Promise(r=>setTimeout(r,ms));
            const clean=v=>(v||'').replace(/\\s+/g,' ').trim();
            const norm=v=>clean(v).replace(/\\(\\d+\\s*farms?\\)/ig,'').replace(/\\bdelete\\b/ig,'').trim().toLowerCase();
            const targetName=${JSONObject.quote(list)};
            const result={x:${x},y:${y},url:location.href,steps:[],troops:{}};
            try{
              const wrappers=[...document.querySelectorAll('#rallyPointFarmList .farmListWrapper')];
              const wrapper=wrappers.find(w=>norm(w.querySelector('.farmListName .name')?.textContent||w.querySelector('.farmListName')?.textContent)===norm(targetName));
              if(!wrapper){result.error='Farm List not found';return JSON.stringify(result);}
              const lid=wrapper.querySelector('.dragAndDrop[data-list]')?.getAttribute('data-list')||'';
              result.lid=lid;
              // Ambil konfigurasi troop langsung dari Farmlist akun yang dipilih.
              [...wrapper.querySelectorAll('input[name],input.unitAmount')].forEach(e=>{
                const n=e.name||''; const v=(e.value||'').trim();
                if(n && /^(t\\d+|u\\d+)$/.test(n) && v) result.troops[n]=v;
              });
              const add=wrapper.querySelector('td.addTarget a, td.addTarget button');
              if(!add){result.error='Add Target button not found';return JSON.stringify(result);}
              add.click(); result.steps.push('open_add_target');
              let form=null;
              for(let i=0;i<50;i++){form=document.querySelector('#farmListTargetForm');if(form)break;await sleep(200);}
              if(!form){result.error='farmListTargetForm did not render';return JSON.stringify(result);}

              const inputs=[...form.querySelectorAll('input')];
              const findInput=(patterns)=>inputs.find(e=>patterns.some(p=>p.test((e.name||'')+' '+(e.id||'')+' '+(e.className||''))));
              const xi=findInput([/^x$/i,/xcoord/i,/coordx/i,/coordinatex/i]);
              const yi=findInput([/^y$/i,/ycoord/i,/coordy/i,/coordinatey/i]);
              if(!xi||!yi){
                result.error='X/Y input not found';
                result.inputs=inputs.map(e=>({name:e.name,id:e.id,type:e.type,value:e.value}));
                return JSON.stringify(result);
              }

              const nativeSet=(e,v)=>{
                const proto=e instanceof HTMLInputElement ? HTMLInputElement.prototype : Object.getPrototypeOf(e);
                const desc=Object.getOwnPropertyDescriptor(proto,'value');
                if(desc&&desc.set) desc.set.call(e,String(v)); else e.value=String(v);
                e.dispatchEvent(new Event('input',{bubbles:true}));
                e.dispatchEvent(new Event('change',{bubbles:true}));
                e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Enter',code:'Enter'}));
                e.dispatchEvent(new Event('blur',{bubbles:true}));
              };
              nativeSet(xi,${x}); nativeSet(yi,${y});
              result.steps.push('coordinates_filled');
              result.actualXY={x:xi.value,y:yi.value};
              if(String(xi.value)!==String(${x}) || String(yi.value)!==String(${y})){
                result.error='X/Y value rejected after setter'; return JSON.stringify(result);
              }

              // Trigger validasi target setelah X/Y benar-benar terisi.
              const trigger=form.querySelector('.targetSelection,.targetSelectionResultWrapper,.troopSelection')||form;
              ['input','change'].forEach(type=>trigger.dispatchEvent(new Event(type,{bubbles:true})));
              trigger.dispatchEvent(new MouseEvent('click',{bubbles:true}));

              // Tunggu hasil target dan isi troop dari Farmlist akun.
              for(let i=0;i<50;i++){
                const err=form.querySelector('.targetSelectionResultWrapper.hasError .targetSelectionValidation.show,.targetSelectionResultWrapper.hasError .customValidationRenderElement');
                if(err&&clean(err.textContent)){result.error='target validation: '+clean(err.textContent).slice(0,180);return JSON.stringify(result);}
                const hasTarget=form.querySelector('.targetSelectionResultWrapper,.troopSelection');
                if(hasTarget) break;
                await sleep(200);
              }

              const troopEntries=Object.entries(result.troops);
              for(const [name,value] of troopEntries){
                const ti=form.querySelector('input[name="'+CSS.escape(name)+'"],input.unitAmount[name="'+CSS.escape(name)+'"]');
                if(ti){nativeSet(ti,value);}
              }
              result.steps.push('troops_filled_from_account_farmlist');

              let save=null;
              for(let i=0;i<50;i++){
                save=form.querySelector('button.save,button[type="submit"],input[type="submit"]');
                if(save && !save.disabled) break;
                await sleep(200);
              }
              if(!save || save.disabled){result.error='Save button stayed disabled';return JSON.stringify(result);}
              save.click(); result.steps.push('save_clicked');
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
                log("FARMLIST TARGET ${index+1}/${coords.size} (${x}|${y}) RESULT: ok=${o.optBoolean("ok")} lid=${o.optString("lid")} actualXY=${o.optJSONObject("actualXY")?.toString() ?: "-"} troops=${o.optJSONObject("troops")?.toString() ?: "{}"} steps=${o.optJSONArray("steps")?.toString() ?: "[]"}")
                if(o.has("error")) log("FARMLIST TARGET ERROR (${x}|${y}): ${o.optString("error")}")
            } catch(e:Exception) { log("FARMLIST RESULT PARSE ERROR: ${e.message}; RAW=${raw.take(1800)}") }
            if(index+1<coords.size) handler.postDelayed({ addFarmTargetsSequentially(list,coords,index+1) },650)
            else log("FARMLIST END processed=${coords.size}")
        }
    }

    private fun bonusRegex(): Regex = Regex("\\{a:r([1-4])\\}[^%]{0,100}(25|50)\\s*%", RegexOption.IGNORE_CASE)
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
    private fun readIntFlexible(t: JSONObject, n: String): Int? = t.optString(n).toIntOrNull() ?: t.opt(n).let { if (it is Number) it.toInt() else null }
    private fun readCoord(t:JSONObject,n:String):Int?=readIntFlexible(t,n) ?: t.optJSONObject("position")?.let{readIntFlexible(it,n)} ?: t.optJSONObject("coordinates")?.let{readIntFlexible(it,n)}

    private fun normalizeServer(value:String):String { var s=value.trim(); if(!s.startsWith("http")) s="https://$s"; return s.trimEnd('/') }
    private fun jsEscape(s:String)=s.replace("\\","\\\\").replace("'","\\'")
    private fun unquoteJs(s:String):String=try{ JSONTokener(s).nextValue()?.toString() ?: "" }catch(_:Exception){s.trim('"').replace("\\\"", "\"").replace("\\n", "\n")}
    private fun cleanJsResult(s:String)=unquoteJs(s).replace("\\u003C","<").replace("\\u003E",">").take(1800)

    private fun log(message:String) {
        val line="${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())} | $message\n"
        logView.append(line)
        logView.post { logView.layout?.let { if (it.lineCount > 0) logView.scrollTo(0, it.getLineTop(it.lineCount - 1)) } }
    }
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
        fun oasisUnoccupiedCount()=readableDatabase.rawQuery("SELECT COUNT(*) FROM oasis WHERE occupied=0",null).use{it.moveToFirst();it.getInt(0)}
        fun oasisOccupiedCount()=readableDatabase.rawQuery("SELECT COUNT(*) FROM oasis WHERE occupied=1",null).use{it.moveToFirst();it.getInt(0)}
        fun travcoOverview(limit:Int):String = readableDatabase.rawQuery("SELECT x,y,village,account,population,distance FROM travco ORDER BY distance ASC LIMIT ?", arrayOf(limit.toString())).use { c ->
            val b=StringBuilder()
            while(c.moveToNext()) {
                b.append("(").append(c.getInt(0)).append("|").append(c.getInt(1)).append(") ")
                    .append(c.getString(2) ?: "").append(" | ")
                    .append(c.getString(3) ?: "").append(" | pop=")
                    .append(c.getInt(4)).append(" | dist=").append(c.getDouble(5)).append("\n")
            }
            b.toString()
        }
        fun oasisOverview(limit:Int):String = readableDatabase.rawQuery("SELECT x,y,oasisType,occupied,animals,owner,alliance FROM oasis ORDER BY y,x LIMIT ?", arrayOf(limit.toString())).use { c ->
            val b=StringBuilder()
            while(c.moveToNext()) {
                b.append("(").append(c.getInt(0)).append("|").append(c.getInt(1)).append(") ")
                    .append(c.getString(2) ?: "").append(" | ")
                    .append(if(c.getInt(3)!=0) "OCC" else "FREE")
                    .append(" | ").append(c.getString(4) ?: "")
                if (!c.getString(5).orEmpty().isBlank()) b.append(" | owner=").append(c.getString(5))
                if (!c.getString(6).orEmpty().isBlank()) b.append(" | alliance=").append(c.getString(6))
                b.append("\n")
            }
            b.toString()
        }
        fun travcoCoords(): List<Pair<Int, Int>> = coords("SELECT x,y FROM travco ORDER BY distance ASC")
        fun oasisCoords(): List<Pair<Int, Int>> = coords("SELECT x,y FROM oasis WHERE occupied=0 ORDER BY id")
        private fun coords(sql:String):List<Pair<Int,Int>>{val r=ArrayList<Pair<Int,Int>>();readableDatabase.rawQuery(sql,null).use{while(it.moveToNext())r.add(it.getInt(0) to it.getInt(1))};return r}
    }
}
