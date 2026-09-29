package ir.khatman.foodiut;

import android.os.Handler;
import android.os.Looper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SniperEngine {

    public interface Log { void onLog(String line); }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // fetchList status codes
    private static final int FETCH_OK = 0;
    private static final int FETCH_RATE_LIMIT = 1;
    private static final int FETCH_AUTH = 2;
    private static final int FETCH_ERROR = 3;

    private final OkHttpClient http;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Log log;
    private volatile boolean running = true;

    public SniperEngine(Log log) {
        this.log = log;
        this.http = new OkHttpClient.Builder()
                .cookieJar(new MemCookieJar())
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public void stop() { running = false; }

    private void log(String s) {
        ui.post(() -> log.onLog(s));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ============================================================
    //  MAIN LOOP
    // ============================================================
    public void run(String username, String password, int mealId, String selfName,
                    int listIntervalMs, int buyIntervalMs, int maxAttempts) {
        try {
            if (!running) return;

            if (!login(username, password)) {
                log("[FAIL] Login failed");
                return;
            }
            log("[OK] Logged in");

            String xsrf = getXsrf();
            if (xsrf == null || xsrf.isEmpty()) {
                log("[!] XSRF token not found");
                return;
            }
            log("[OK] XSRF: " + shortStr(xsrf));

            int attempt = 0;
            while (running) {
                attempt++;
                log("");
                log("PHASE 1 - check #" + attempt);

                // credit
                Integer credit = getCredit(xsrf);
                if (credit != null) log("  [*] Credit: " + credit);

                // list
                FetchResult fr = fetchList(mealId, xsrf);

                if (fr.status == FETCH_RATE_LIMIT) {
                    log("  [!] Rate limited. Sleeping 30s...");
                    sleep(30000);
                    continue;
                }
                if (fr.status == FETCH_AUTH) {
                    log("  [!] Session expired, re-login");
                    if (!login(username, password)) {
                        log("  [FAIL] re-login failed");
                        return;
                    }
                    xsrf = getXsrf();
                    if (xsrf == null || xsrf.isEmpty()) {
                        log("  [!] XSRF lost after re-login");
                        return;
                    }
                    continue;
                }
                if (fr.status == FETCH_ERROR) {
                    log("  [-] Fetch error");
                    sleep(listIntervalMs);
                    continue;
                }

                List<Food> foods = fr.foods;
                if (foods == null || foods.isEmpty()) {
                    log("  [-] No food available");
                    sleep(listIntervalMs);
                    continue;
                }
                log("  [*] " + foods.size() + " item(s) in list");

                List<Food> buyable = new ArrayList<>();
                for (Food f : foods) {
                    if (f.foodId <= 0) continue;
                    if (selfName != null && !selfName.isEmpty()
                            && !f.selfName.contains(selfName)) continue;
                    if (credit != null && f.price > credit) continue;
                    buyable.add(f);
                }
                if (buyable.isEmpty()) {
                    log("  [-] No buyable food for '" + selfName + "'");
                    sleep(listIntervalMs);
                    continue;
                }

                Collections.sort(buyable, (a, b) -> Integer.compare(a.price, b.price));

                log("  [OK] " + buyable.size() + " buyable:");
                int show = Math.min(5, buyable.size());
                for (int i = 0; i < show; i++) {
                    Food f = buyable.get(i);
                    log("      FoodID=" + f.foodId + "  " + f.foodName
                            + "  Date=" + f.date + "  Price=" + f.price);
                }

                Food chosen = buyable.get(0);
                log("");
                log("  >>> Selected: " + chosen.foodName
                        + " (FoodID=" + chosen.foodId + ", Date=" + chosen.date + ")");

                if (buyLoop(chosen, xsrf, buyIntervalMs, maxAttempts)) {
                    log("");
                    log("============================================================");
                    log("[SUCCESS] PURCHASED!");
                    log("============================================================");
                    return;
                }

                sleep(1000);
            }
        } catch (Exception e) {
            log("[!] Fatal: " + e.getMessage());
        }
    }

    // ============================================================
    //  LOGIN (CAS)
    // ============================================================
    private boolean login(String user, String pass) throws IOException {
        // 1) GET / -> end at identity/login?signin=XXX
        Resp r = follow("https://dining.iut.ac.ir/", 5);
        if (r == null) return false;

        String signin = HttpUrl.parse(r.finalUrl).queryParameter("signin");
        if (signin == null) return false;

        // 2) GET /identity/external?provider=CAS&signin=XXX
        Request req = new Request.Builder()
                .url("https://dining.iut.ac.ir/identity/external?provider=CAS&signin=" + signin)
                .build();
        Response resp = http.newCall(req).execute();
        int code = resp.code();
        String loc = resp.header("Location");
        resp.close();
        if (code != 302 || loc == null) return false;

        // 3) GET CAS page
        r = follow(loc, 5);
        if (r == null) return false;

        Document doc = Jsoup.parse(r.body);
        Element form = doc.selectFirst("form#fm1");
        if (form == null) form = doc.selectFirst("form");
        if (form == null) return false;

        String action = form.attr("action");
        Map<String, String> hidden = new HashMap<>();
        for (Element inp : form.select("input[type=hidden]")) {
            String n = inp.attr("name");
            if (!n.isEmpty()) hidden.put(n, inp.attr("value"));
        }

        HttpUrl base = HttpUrl.parse(r.finalUrl);
        HttpUrl postUrl = base.resolve(action);
        if (postUrl == null) return false;

        // 4) POST credentials
        FormBody body = new FormBody.Builder()
                .add("username", user)
                .add("password", pass)
                .add("execution", hidden.getOrDefault("execution", ""))
                .add("_eventId", hidden.getOrDefault("_eventId", "submit"))
                .add("geolocation", "")
                .build();

        Request post = new Request.Builder()
                .url(postUrl)
                .post(body)
                .header("Referer", r.finalUrl)
                .header("Origin", "https://webauth.iut.ac.ir")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();

        Response pr = http.newCall(post).execute();
        code = pr.code();
        loc = pr.header("Location");
        pr.close();

        if (code < 300 || code >= 400 || loc == null) return false;

        // 5) Follow redirects until we land back on dining
        String current = loc;
        String lastHost = "webauth.iut.ac.ir";
        for (int i = 0; i < 15; i++) {
            if (current.startsWith("/")) current = "https://" + lastHost + current;
            HttpUrl u = HttpUrl.parse(current);
            if (u == null) break;
            lastHost = u.host();

            Request next = new Request.Builder().url(current).build();
            Response rr = http.newCall(next).execute();
            int c = rr.code();
            String l = rr.header("Location");
            rr.close();
            if (c >= 300 && c < 400 && l != null) {
                current = l;
            } else break;
        }

        // 6) Verify
        r = follow("https://dining.iut.ac.ir/", 5);
        return r != null && !r.finalUrl.contains("identity/login");
    }

    // ============================================================
    //  XSRF TOKEN
    // ============================================================
    private String getXsrf() {
        try {
            Resp r = follow("https://dining.iut.ac.ir/", 3);
            if (r == null) return null;
            Pattern[] pats = {
                    Pattern.compile("<input[^>]*name=[\"']__RequestVerificationToken[\"'][^>]*value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<input[^>]*value=[\"']([^\"']+)[\"'][^>]*name=[\"']__RequestVerificationToken[\"']", Pattern.CASE_INSENSITIVE)
            };
            for (Pattern p : pats) {
                Matcher m = p.matcher(r.body);
                if (m.find()) return m.group(1);
            }
            Document d = Jsoup.parse(r.body);
            Element e = d.selectFirst("input[name=__RequestVerificationToken]");
            if (e != null && !e.attr("value").isEmpty()) return e.attr("value");

            // Fallback: از کوکی
            List<Cookie> cookies = http.cookieJar().loadForRequest(
                    HttpUrl.parse("https://dining.iut.ac.ir/"));
            for (Cookie c : cookies) {
                if ("__RequestVerificationToken".equals(c.name())) {
                    return c.value();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ============================================================
    //  CREDIT
    // ============================================================
    private Integer getCredit(String xsrf) {
        try {
            Request req = new Request.Builder()
                    .url("https://dining.iut.ac.ir/api/v0/Credit")
                    .header("X-XSRF-Token", xsrf)
                    .header("Accept", "application/json, text/plain, */*")
                    .build();
            Response r = http.newCall(req).execute();
            if (r.code() != 200) { r.close(); return null; }
            String b = r.body() != null ? r.body().string() : "";
            r.close();
            try { return Integer.parseInt(b.trim()); } catch (Exception e) { return null; }
        } catch (Exception e) { return null; }
    }

    // ============================================================
    //  FETCH FOOD LIST
    // ============================================================
    private FetchResult fetchList(int mealId, String xsrf) {
        FetchResult fr = new FetchResult();
        try {
            HttpUrl url = HttpUrl.parse("https://dining.iut.ac.ir/api/v0/TransferFoodBuy")
                    .newBuilder()
                    .addQueryParameter("mealId", String.valueOf(mealId))
                    .addQueryParameter("status", "123")
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .header("X-XSRF-Token", xsrf)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://dining.iut.ac.ir/")
                    .build();
            Response r = http.newCall(req).execute();
            int code = r.code();
            if (code == 429) { r.close(); fr.status = FETCH_RATE_LIMIT; return fr; }
            if (code == 401 || code == 403) { r.close(); fr.status = FETCH_AUTH; return fr; }
            if (code != 200) { r.close(); fr.status = FETCH_ERROR; return fr; }
            String b = r.body() != null ? r.body().string() : "[]";
            r.close();

            JSONArray arr = new JSONArray(b);
            List<Food> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Food f = new Food();
                f.foodId = o.optInt("FoodID", 0);
                f.foodName = o.optString("FoodName", "");
                f.mealId = o.optInt("MealID", mealId);
                f.selfId = o.optInt("SelfID", 0);
                f.selfName = o.optString("SelfName", "");
                f.price = o.optInt("BuyPrice", 0);
                f.date = o.optString("Date", "");
                out.add(f);
            }
            fr.status = FETCH_OK;
            fr.foods = out;
            return fr;
        } catch (Exception e) {
            fr.status = FETCH_ERROR;
            return fr;
        }
    }

    // ============================================================
    //  BUY
    // ============================================================
    private boolean buyLoop(Food f, String xsrf, int buyIntervalMs, int maxAttempts) {
        log("");
        log("PHASE 2: BUY LOOP  (" + maxAttempts + " x " + buyIntervalMs + "ms)");
        for (int i = 1; i <= maxAttempts && running; i++) {
            int code;
            int stateCode = -1;
            String stateMsg = "";
            String respText = "";
            try {
                JSONObject o = new JSONObject();
                o.put("Date", f.date);
                o.put("MealID", f.mealId);
                o.put("FoodID", f.foodId);
                o.put("SelfID", f.selfId);
                o.put("BuyPrice", f.price);
                o.put("BuyCount", 1);

                RequestBody rb = RequestBody.create(o.toString(), JSON);
                Request req = new Request.Builder()
                        .url("https://dining.iut.ac.ir/api/v0/TransferFoodBuy")
                        .post(rb)
                        .header("X-XSRF-Token", xsrf)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .header("Origin", "https://dining.iut.ac.ir")
                        .header("Referer", "https://dining.iut.ac.ir/")
                        .build();

                Response r = http.newCall(req).execute();
                code = r.code();
                respText = r.body() != null ? r.body().string() : "";
                r.close();

                try {
                    JSONObject rb2 = new JSONObject(respText);
                    stateCode = rb2.optInt("StateCode", -1);
                    stateMsg = rb2.optString("StateMessage", "");
                } catch (Exception ignored) {}

                if (code == 200 && stateCode == 0) {
                    log("  #" + pad(i) + "  [SUCCESS] " + stateMsg);
                    return true;
                }
                if (code == 200 && stateCode != -1) {
                    log("  #" + pad(i) + "  [200/Code=" + stateCode + "] " + stateMsg);
                    return false;
                }
                if (i == 1 || i % 5 == 0) {
                    log("  #" + pad(i) + "  [" + code + "]  " + shortStr(respText));
                }
            } catch (Exception e) {
                if (i == 1 || i % 5 == 0) log("  #" + pad(i) + "  ERR " + e.getMessage());
            }
            sleep(buyIntervalMs);
        }
        return false;
    }

    // ============================================================
    //  HELPERS
    // ============================================================
    private static class Resp {
        String finalUrl;
        int code;
        String body;
    }

    private static class FetchResult {
        int status;
        List<Food> foods;
    }

    private Resp follow(String start, int maxHops) throws IOException {
        String cur = start;
        for (int i = 0; i < maxHops; i++) {
            Request req = new Request.Builder().url(cur).build();
            Response r = http.newCall(req).execute();
            int c = r.code();
            String loc = r.header("Location");
            if (c >= 300 && c < 400 && loc != null) {
                r.close();
                HttpUrl base = HttpUrl.parse(cur);
                HttpUrl next = base.resolve(loc);
                if (next == null) return null;
                cur = next.toString();
            } else {
                Resp resp = new Resp();
                resp.finalUrl = cur;
                resp.code = c;
                resp.body = r.body() != null ? r.body().string() : "";
                r.close();
                return resp;
            }
        }
        return null;
    }

    private static String pad(int n) {
        if (n < 10) return "00" + n;
        if (n < 100) return "0" + n;
        return String.valueOf(n);
    }

    private static String shortStr(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static class Food {
        int foodId;
        String foodName;
        int mealId;
        int selfId;
        String selfName;
        int price;
        String date;
    }

    // ============================================================
    //  COOKIE JAR (FIXED: merge by name, not replace)
    // ============================================================
    static class MemCookieJar implements CookieJar {
        private final Map<String, Map<String, Cookie>> store = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            String host = url.host();
            Map<String, Cookie> hostStore = store.computeIfAbsent(host, k -> new HashMap<>());
            for (Cookie c : cookies) {
                hostStore.put(c.name(), c);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            Map<String, Cookie> hostStore = store.get(url.host());
            if (hostStore == null) return new ArrayList<>();
            return new ArrayList<>(hostStore.values());
        }
    }
}