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
import android.widget.TextView
import android.widget.CheckBox
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
    private lateinit var farmListChecks: LinearLayout
    private val farmListNames = mutableListOf<String>()
    private val selectedFarmLists = linkedSetOf<String>()
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
        farmListChecks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.rgb(60, 60, 60))
        }
        unitInput = edit("Unit (default t1)", "t1")
        countInput = edit("Jumlah unit (default 20)", "20")

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

        content.addView(label("FARMLIST AKUN (CHECKLIST)", 20f))
        content.addView(farmListChecks)
        content.addView(button("REFRESH FARMLIST DARI AKUN") { loadFarmLists() })
        // Hidden/unused text field is kept only for compatibility with older code.
        farmListInput.visibility = android.view.View.GONE
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
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
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
                    if (url.contains("karte.php") || url.contains("dorf") || url.contains("build.php")) {
                        handler.postDelayed({ loadFarmLists(false) }, 1200)
                    }
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
        // /api/v1/map/position returns the useful tile metadata for oasis
        // detection at zoomLevel=2. Keep a small overlap between requests
        // so no tile is missed when scanning the requested radius.
        val step = 20
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
                const payload={data:{x:$x,y:$y,zoomLevel:2,ignorePositions:[]}};
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

    private fun selectedFarmListNames(): List<String> {
        return selectedFarmLists.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun rebuildFarmListChecks() {
        farmListChecks.removeAllViews()
        val oldSelected = selectedFarmLists.toSet()
        selectedFarmLists.clear()
        for (name in farmListNames) {
            val cb = CheckBox(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 16f
                isChecked = oldSelected.contains(name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedFarmLists.add(name) else selectedFarmLists.remove(name)
                }
            }
            if (cb.isChecked) selectedFarmLists.add(name)
            farmListChecks.addView(cb, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loadFarmLists(navigate: Boolean = true) {
        val server = normalizeServer(serverInput.text.toString())
        if (server.isBlank()) {
            log("FARMLIST LOAD ERROR: server kosong")
            return
        }
        log("FARMLIST LOAD START")
        val run = {
            val js = """
              (function(){
                const clean=v=>(v||'').replace(/\s+/g,' ').trim();
                const out=[];
                const seen=new Set();
                const add=(name)=>{
                  name=clean(name).replace(/\(\d+\s+farms?\)/ig,'').trim();
                  if(name && !seen.has(name.toLowerCase())){seen.add(name.toLowerCase());out.push(name);}
                };
                document.querySelectorAll('#rallyPointFarmList .farmListWrapper').forEach(w=>{
                  add(w.querySelector('.farmListName .name')?.textContent ||
                      w.querySelector('.farmListName')?.textContent ||
                      w.querySelector('[data-list]')?.getAttribute('data-list-name'));
                });
                if(!out.length){
                  document.querySelectorAll('.farmListWrapper').forEach(w=>{
                    add(w.querySelector('.farmListName .name')?.textContent ||
                        w.querySelector('.farmListName')?.textContent ||
                        w.getAttribute('data-list-name'));
                  });
                }
                return JSON.stringify({ok:true,url:location.href,count:out.length,names:out});
              })();
            """.trimIndent()
            webView.evaluateJavascript(js) { result ->
                val raw=unquoteJs(result)
                try {
                    val o=JSONObject(raw)
                    val arr=o.optJSONArray("names") ?: JSONArray()
                    farmListNames.clear()
                    for(i in 0 until arr.length()) farmListNames.add(arr.optString(i))
                    rebuildFarmListChecks()
                    if(farmListNames.isNotEmpty()) {
                        farmListInput.setText(farmListNames[0])
                        log("FARMLIST LOAD END: ${farmListNames.size} list ditemukan: ${farmListNames.joinToString(" | ")}")
                    } else {
                        log("FARMLIST LOAD END: 0 list. URL=${o.optString("url")}")
                    }
                } catch(e:Exception) {
                    log("FARMLIST LOAD PARSE ERROR: ${e.message}; RAW=${raw.take(1800)}")
                }
            }
        }
        if (navigate && !currentPage.contains("build.php?gid=16", ignoreCase=true) && !currentPage.contains("build.php?gid=16&", ignoreCase=true)) {
            pageReady=false
            webView.loadUrl("$server/build.php?gid=16&tt=99")
            handler.postDelayed(run, 3500)
        } else {
            run()
        }
    }

    private fun addTravcoToFarmList() { addFromDb(false) }
    private fun addOasisToFarmList() { addFromDb(true) }

    private fun addFromDb(oasis:Boolean) {
        val lists=selectedFarmListNames()
        val unit=unitInput.text.toString().trim().ifBlank { "t1" }
        val count=countInput.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 20
        if(lists.isEmpty()) {
            log("FARMLIST ERROR: checklist farmlist belum dipilih")
            loadFarmLists()
            return
        }
        val rows=if(oasis) db.oasisCoords() else db.travcoCoords()
        if(oasis) {
            log("FARMLIST START source=OASIS lists=${lists.joinToString(" | ")} targets=${rows.size} (maks 100 target/list, troops tidak di-set)")
        } else {
            log("FARMLIST START source=TRAVCO lists=${lists.joinToString(" | ")} targets=${rows.size} (maks 100 target/list) unit=$unit count=$count")
        }
        if(rows.isEmpty()) { log("FARMLIST ERROR: database source empty"); return }

        val requiredLists=ceil(rows.size / 100.0).toInt()
        if(lists.size < requiredLists) {
            log("FARMLIST ERROR: ${rows.size} target membutuhkan $requiredLists farmlist (100 target/list), tetapi hanya ${lists.size} checklist")
            return
        }

        val server=normalizeServer(serverInput.text.toString())
        webView.loadUrl("$server/build.php?gid=16&tt=99")
        handler.postDelayed({
            val firstEnd=minOf(100, rows.size)
            log("FARMLIST BATCH 1/${lists.size}: '${lists[0]}' targets 1-$firstEnd")
            addFarmTargetsSequentially(lists[0], if(oasis) "" else unit, if(oasis) 0 else count, rows.subList(0, firstEnd), 0, oasis, lists, 0, rows)
        },4500)
    }

    private fun addFarmTargetsSequentially(list:String,unit:String,count:Int,coords:List<Pair<Int,Int>>,index:Int,oasis:Boolean=false,allLists:List<String> = listOf(list),listIndex:Int=0,allRows:List<Pair<Int,Int>> = coords) {
        if(index>=coords.size) {
            val completedBefore = listIndex * 100 + coords.size
            if(completedBefore < 1) { log("FARMLIST END completed=0"); return }
            val nextStart = completedBefore
            if(nextStart < (if(oasis) db.oasisCoords().size else db.travcoCoords().size)) {
                val nextListIndex = listIndex + 1
                if(nextListIndex >= allLists.size) { log("FARMLIST ERROR: farmlist tidak cukup untuk sisa target"); return }
                val nextEnd=minOf(nextStart + 100, allRows.size)
                log("FARMLIST NEXT: '${allLists[nextListIndex]}' targets ${nextStart+1}-$nextEnd")
                log("FARMLIST BATCH ${nextListIndex+1}/${allLists.size}: '${allLists[nextListIndex]}' targets ${nextStart+1}-$nextEnd")
                // Reload the Farmlist page before changing to the next selected list.
                // This keeps the same proven Add Target -> X -> Y -> unitAmount -> Save flow.
                val server=normalizeServer(serverInput.text.toString())
                pageReady=false
                webView.loadUrl("$server/build.php?gid=16&tt=99")
                handler.postDelayed({
                    addFarmTargetsSequentially(allLists[nextListIndex],unit,count,allRows.subList(nextStart,nextEnd),0,oasis,allLists,nextListIndex,allRows)
                },3500)
            } else {
                log("FARMLIST END completed=$completedBefore")
            }
            return
        }
        val (x,y)=coords[index]
        val token = "travcoFarmResult_${System.currentTimeMillis()}_${index}"
        val js="""
          (function(){
            const KEY=${JSONObject.quote(token)};
            window[KEY]=null;
            const sleep=ms=>new Promise(r=>setTimeout(r,ms));
            const clean=v=>(v||'').replace(/\\s+/g,' ').trim();
            const norm=v=>clean(v).replace(/\\(\\d+\\s+farms?\\)/ig,'').replace(/\\bdelete\\b/ig,'').trim().toLowerCase();
            const targetName=${JSONObject.quote(list)};
            const troop=${JSONObject.quote(unit)};
            const amount=${count};
            const result={x:${x},y:${y},url:location.href,steps:[]};
            (async function(){
              try{
                if(!location.href.includes('gid=16')) result.steps.push('wrong_page');

                // Travian/RoG can render the farm-list panel in different containers.
                let wrappers=[...document.querySelectorAll('#rallyPointFarmList .farmListWrapper')];
                if(!wrappers.length) wrappers=[...document.querySelectorAll('.farmListWrapper')];
                const wrapper=wrappers.find(w=>{
                  const n=w.querySelector('.farmListName .name')?.textContent ||
                          w.querySelector('.farmListName')?.textContent ||
                          w.getAttribute('data-list-name') || '';
                  return norm(n)===norm(targetName);
                });
                if(!wrapper){
                  result.error='Farm List not found: '+targetName;
                  result.available=wrappers.map(w=>clean(w.innerText).slice(0,150));
                  window[KEY]=result; return;
                }
                result.listText=clean(wrapper.innerText).slice(0,250);

                // First try the actual Add Target/Add Farm control used by Travian.
                let add=wrapper.querySelector('td.addTarget a,td.addTarget button,.addTarget a,.addTarget button');
                if(!add) add=wrapper.querySelector('[data-action*="add" i],[class*="addTarget" i] a,[class*="addTarget" i] button');
                if(!add){
                  const candidates=[...wrapper.querySelectorAll('a,button,input[type="button"],input[type="submit"]')];
                  add=candidates.find(e=>{
                    const txt=clean(e.innerText||e.value||e.getAttribute('title')||e.getAttribute('aria-label'));
                    return /add\\s*(target|farm)|target\\s*add|farm\\s*list/i.test(txt);
                  });
                }
                if(!add){ result.error='Add Target button not found'; window[KEY]=result; return; }
                add.click(); result.steps.push('open_add_target');

                let form=null;
                for(let i=0;i<80;i++){
                  form=document.querySelector('#farmListTargetForm,form.farmListTargetForm,.farmListTargetForm');
                  if(form) break;
                  await sleep(150);
                }
                if(!form){
                  // Fallback: any visible dialog/form containing coordinate inputs.
                  form=[...document.querySelectorAll('form')].find(f=>{
                    const xs=f.querySelector('input[name="x"],input[name="xCoord"],input[id*="xCoord" i]');
                    const ys=f.querySelector('input[name="y"],input[name="yCoord"],input[id*="yCoord" i]');
                    return xs&&ys;
                  });
                }
                if(!form){
                  result.error='Farm target form did not render';
                  result.body=clean(document.body?.innerText).slice(-1800);
                  window[KEY]=result; return;
                }

                const inputs=[...form.querySelectorAll('input')];
                //const xi=form.querySelector('input[name="x"],input[name="xCoord"],input[id*="xCoord" i]') ||
                         //inputs.find(e=>/^(x|xcoord|coordx)$/i.test(e.name||e.id));
                //const yi=form.querySelector('input[name="y"],input[name="yCoord"],input[id*="yCoord" i]') ||
                       //  inputs.find(e=>/^(y|ycoord|coordy)$/i.test(e.name||e.id));
               // if(!xi||!yi){ result.error='X/Y input not found'; window[KEY]=result; return; }



// PERBAIKAN UTAMA: Selector spesifik untuk DOM dengan wrapper .coordinateX / .coordinateY
            const xi=form.querySelector('.coordinateX input, input[name="x"]') ||
                     [...form.querySelectorAll('input')].find(e=>/^(x|xcoord|coordx)$/i.test(e.name||e.id));
            const yi=form.querySelector('.coordinateY input, input[name="y"]') ||
                     [...form.querySelectorAll('input')].find(e=>/^(y|ycoord|coordy)$/i.test(e.name||e.id));
            if(!xi||!yi){ result.error='X/Y input not found'; window[KEY]=result; return; }

            

                // Ketik koordinat satu karakter demi satu karakter. Travian memakai
                // listener keyboard/input pada field koordinat, sehingga jangan langsung
                // mengganti value menjadi seluruh koordinat sekaligus.
                const typeByCharacter=async(e,v)=>{
                  const proto=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                  const value=String(v);
                  try{ e.focus(); }catch(err){}
                  if(proto&&proto.set) proto.set.call(e,''); else e.value='';
                  e.dispatchEvent(new Event('input',{bubbles:true}));
                  for(const ch of value){
                    const key=(ch==='-' ? 'Minus' : ch);
                    const code=(ch==='-' ? 'Minus' : ('Digit'+ch));
                    const keyCode=(ch==='-' ? 189 : (ch.charCodeAt(0)-48));
                    e.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:ch,code:code,keyCode:keyCode,which:keyCode}));
                    const current=String(e.value||'');
                    if(proto&&proto.set) proto.set.call(e,current+ch); else e.value=current+ch;
                    e.dispatchEvent(new Event('input',{bubbles:true}));
                    e.dispatchEvent(new KeyboardEvent('keypress',{bubbles:true,key:ch,code:code,keyCode:keyCode,which:keyCode}));
                    e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:ch,code:code,keyCode:keyCode,which:keyCode}));
                    await sleep(120);
                  }
                  e.dispatchEvent(new Event('change',{bubbles:true}));
                };

                await typeByCharacter(xi,${x});
                result.steps.push('X_typed_char_by_char=${x}');
                await typeByCharacter(yi,${y});
                result.steps.push('Y_typed_char_by_char=${y}');

                let save=null;

                // Untuk OASIS: setelah X/Y diisi, pindahkan focus ke input unitAmount.
                // Perpindahan focus ini membuat field koordinat kehilangan focus sehingga
                // Travian menjalankan lookup target/village secara asynchronous.
                // Tunggu 2 detik agar hasil lookup masuk dan tombol Save menjadi aktif.
                if(${oasis}) {
                  let currentForm=form;
                  const troopFocus=currentForm.querySelector('input.unitAmount') ||
                    currentForm.querySelector('input.unitAmount[name="t1"]') ||
                    document.querySelector('input.unitAmount[name="t1"]') ||
                    document.querySelector('input.unitAmount');
                  if(!troopFocus){
                    result.error='unitAmount input not found';
                    window[KEY]=result; return;
                  }
                  try {
                    troopFocus.scrollIntoView({block:'center',inline:'center'});
                    troopFocus.focus();
                    result.steps.push('unitAmount_focused');
                  } catch(e) {
                    result.error='unitAmount focus failed: '+String(e&&e.message||e);
                    window[KEY]=result; return;
                  }

                  await sleep(2000);
                  result.steps.push('unitAmount_focus_wait=2000ms');

                  // React/Travian dapat mengganti form setelah lookup koordinat.
                  // Ambil kembali form dan tombol Save terbaru sebelum klik.
                  currentForm=document.querySelector('#farmListTargetForm,form.farmListTargetForm,.farmListTargetForm') ||
                    [...document.querySelectorAll('form')].find(f=>{
                      const xs=f.querySelector('input[name="x"]');
                      const ys=f.querySelector('input[name="y"]');
                      return xs&&ys;
                    }) || currentForm;
                  form=currentForm;

                  for(let i=0;i<20;i++){
                    save=form?.querySelector('button.textButtonV2.buttonFramed.save.rectangle.withText.green[type="submit"]') ||
                         form?.querySelector('button.textButtonV2.buttonFramed.save.rectangle.withText.green') ||
                         form?.querySelector('button.save[type="submit"]');
                    if(save && !save.disabled) break;
                    await sleep(250);
                  }
                  if(!save){
                    result.error='Save button not found after unitAmount focus';
                    result.formText=clean(form?.innerText).slice(0,2200);
                    window[KEY]=result; return;
                  }
                  result.steps.push('save_found_disabled='+!!save.disabled);
                  if(save.disabled){
                    result.error='Save button still disabled after unitAmount focus wait';
                    result.formText=clean(form?.innerText).slice(0,2200);
                    window[KEY]=result; return;
                  }
                } else {
                  // Jalur lama untuk Travco tetap boleh mengisi troops.

                  let troopInput=form.querySelector('input[name="'+troop+'"],input.unitAmount[name="'+troop+'"]');
                  if(!troopInput){
                    troopInput=[...form.querySelectorAll('input.unitAmount,input[type="text"],input[type="number"]')]
                      .find(e=>(e.name||'').toLowerCase()===troop.toLowerCase());
                  }
                  if(troopInput){
                    set(troopInput,amount);
                    result.steps.push('troop_filled');
                  } else result.steps.push('troop_input_not_found');

                  await sleep(300);
                }

                for(let i=0;i<20;i++){
                  save=form.querySelector('button.textButtonV2.buttonFramed.save.rectangle.withText.green[type="submit"]') ||
                       form.querySelector('button.textButtonV2.buttonFramed.save.rectangle.withText.green') ||
                       form.querySelector('button.save[type="submit"]');
                  if(save){
                    result.steps.push('save_found_disabled='+!!save.disabled);
                    if(!save.disabled) break;
                  }
                  await sleep(250);
                }
                if(!save){
                  result.error='Save button not found';
                  result.formText=clean(form.innerText).slice(0,1600);
                  window[KEY]=result; return;
                }
                if(save.disabled){
                  result.error='Save button still disabled after village lookup';
                  result.formText=clean(form.innerText).slice(0,1600);
                  window[KEY]=result; return;
                }
                try {
                  save.scrollIntoView({block:'center',inline:'center'});
                } catch(e) {}
                await sleep(200);
                save.click();
                result.steps.push('save_clicked');

                // Wait for the modal/form to disappear OR the target to appear in the list.
                for(let i=0;i<80;i++){
                  const still=document.querySelector('#farmListTargetForm,form.farmListTargetForm,.farmListTargetForm');
                  const body=clean(wrapper.innerText);
                  const coordSeen=body.includes('(${x}|${y})') || (body.includes('${x}') && body.includes('${y}'));
                  if(!still || coordSeen){ result.ok=true; window[KEY]=result; return; }
                  await sleep(150);
                }
                result.error='Save clicked but form remained open';
                window[KEY]=result;
              }catch(e){
                result.error=String(e&&e.message||e);
                window[KEY]=result;
              }
            })();
            return KEY;
          })();
        """.trimIndent()

        // evaluateJavascript() does NOT wait for a JavaScript Promise. The old code
        // evaluated an async IIFE directly, so Android received the Promise/empty
        // result and the log showed steps=[] even though the JS had not finished.
        webView.evaluateJavascript(js) { keyResult ->
            val key=unquoteJs(keyResult).ifBlank { token }
            pollFarmListResult(key, list,unit,count,coords,index,0,oasis)
        }
    }

    private fun pollFarmListResult(key:String,list:String,unit:String,count:Int,coords:List<Pair<Int,Int>>,index:Int,attempt:Int,oasis:Boolean) {
        webView.evaluateJavascript("window[${JSONObject.quote(key)}] ? JSON.stringify(window[${JSONObject.quote(key)}]) : ''") { result ->
            val raw=unquoteJs(result)
            if(raw.isBlank() && attempt < 100) {
                handler.postDelayed({ pollFarmListResult(key,list,unit,count,coords,index,attempt+1,oasis) },150)
                return@evaluateJavascript
            }
            val (x,y)=coords[index]
            try {
                val o=JSONObject(raw)
                log("FARMLIST TARGET ${index+1}/${coords.size} (${x}|${y}) RESULT: ok=${o.optBoolean("ok")} steps=${o.optJSONArray("steps")?.toString() ?: "[]"}")
                if(o.has("error")) log("FARMLIST TARGET ERROR (${x}|${y}): ${o.optString("error").take(1400)}")
                if(o.has("available")) log("FARMLIST AVAILABLE: ${o.optJSONArray("available")?.toString()?.take(1200)}")
            } catch(e:Exception) {
                log("FARMLIST RESULT PARSE ERROR: ${e.message}; RAW=${raw.take(1800)}")
            }
            webView.evaluateJavascript("try{delete window[${JSONObject.quote(key)}];}catch(e){}",null)
            if(index+1<coords.size) handler.postDelayed({ addFarmTargetsSequentially(list,unit,count,coords,index+1,oasis,allLists,listIndex,allRows) },900)
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
