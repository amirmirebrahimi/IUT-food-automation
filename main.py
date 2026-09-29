import requests
from bs4 import BeautifulSoup
from urllib.parse import urljoin, urlparse, parse_qs
import json
import os
import re
import time
from datetime import datetime


SESSION_FILE = "session.json"
LIST_POLL_INTERVAL = 3.5
BUY_INTERVAL = 2
BUY_MAX_ATTEMPTS = 100
BUY_RATE_LIMIT_SLEEP = 15
MAX_RUNTIME_MIN = 180
CENTRAL_SELF_NAME = "سلف مرکزی"


def get_inputs():
    print("=" * 60)
    print("  IUT Dining Sniper")
    print("=" * 60)
    print()
    username = input("Student ID: ").strip()
    password = input("Password: ").strip()

    print("\nSelect meal:")
    print("  1. Breakfast")
    print("  2. Lunch")
    print("  3. Dinner")
    print("  4. Sahari")
    print("  5. Iftar")
    print("  6. Morning Snack")
    print("  7. Evening Snack")
    choice = input("Number (default 2 = Lunch): ").strip() or "2"

    meal_map = {
        "1": ("صبحانه", 1),
        "2": ("ناهار", 2),
        "3": ("شام", 3),
        "4": ("سحری", 6),
        "5": ("افطاری", 7),
        "6": ("میان وعده صبح", 8),
        "7": ("میان وعده عصر", 9),
    }
    meal_name, meal_id = meal_map.get(choice, ("ناهار", 2))
    print(f"\n[OK] Meal: {meal_name} (MealID={meal_id})")
    return username, password, meal_name, meal_id


def login_via_cas(username, password):
    s = requests.Session()
    s.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                      '(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'fa,en-US;q=0.9,en;q=0.8',
    })
    print("\n[*] Logging in...")
    r1 = s.get("https://dining.iut.ac.ir/", allow_redirects=True, timeout=20)
    if 'signin=' not in r1.url:
        return None
    signin = parse_qs(urlparse(r1.url).query)['signin'][0]
    ext = f"https://dining.iut.ac.ir/identity/external?provider=CAS&signin={signin}"
    r2 = s.get(ext, allow_redirects=False, timeout=20)
    loc2 = r2.headers.get('Location', '')
    r3 = s.get(loc2, allow_redirects=True, timeout=20)
    soup = BeautifulSoup(r3.text, 'html.parser')
    form = soup.find('form', {'id': 'fm1'}) or soup.find('form')
    if not form:
        return None
    hidden = {inp.get('name'): inp.get('value', '')
              for inp in form.find_all('input', {'type': 'hidden'}) if inp.get('name')}
    post_url = urljoin(r3.url, form.get('action', 'login'))
    r4 = s.post(post_url, data={
        'username': username,
        'password': password,
        'execution': hidden.get('execution', ''),
        '_eventId': hidden.get('_eventId', 'submit'),
        'geolocation': '',
    }, headers={
        'Referer': r3.url,
        'Origin': 'https://webauth.iut.ac.ir',
        'Content-Type': 'application/x-www-form-urlencoded',
    }, allow_redirects=False, timeout=20)
    if r4.status_code not in (301, 302, 303):
        err = BeautifulSoup(r4.text, 'html.parser').find('div', {'class': 'alert alert-danger'})
        if err:
            print(f"[!] {err.get_text(strip=True)}")
        return None
    current_url = r4.headers.get('Location', '')
    current_host = None
    for _ in range(15):
        if current_url.startswith('/'):
            current_url = f"https://{current_host or 'dining.iut.ac.ir'}{current_url}"
        current_host = urlparse(current_url).netloc
        r5 = s.get(current_url, allow_redirects=False, timeout=20)
        if r5.status_code in (301, 302, 303, 307, 308):
            current_url = r5.headers.get('Location', '')
        else:
            break
    verify = s.get("https://dining.iut.ac.ir/", allow_redirects=True, timeout=20)
    if 'identity/login' in verify.url:
        return None
    print("[OK] Logged in")
    return s


def save_session(s, filename=SESSION_FILE):
    cookies = [{'name': c.name, 'value': c.value,
                'domain': c.domain, 'path': c.path} for c in s.cookies]
    with open(filename, 'w', encoding='utf-8') as f:
        json.dump(cookies, f, ensure_ascii=False, indent=2)


def load_session(filename=SESSION_FILE):
    s = requests.Session()
    s.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                      '(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36',
        'Accept-Language': 'fa,en-US;q=0.9,en;q=0.8',
    })
    with open(filename, 'r', encoding='utf-8') as f:
        for c in json.load(f):
            s.cookies.set(c['name'], c['value'],
                          domain=c.get('domain', 'dining.iut.ac.ir'),
                          path=c.get('path', '/'))
    return s


def get_real_xsrf_token(s):
    try:
        r = s.get("https://dining.iut.ac.ir/", timeout=15)
        html = r.text
        patterns = [
            r'<input[^>]*name=["\']__RequestVerificationToken["\'][^>]*value=["\']([^"\']+)["\']',
            r'<input[^>]*value=["\']([^"\']+)["\'][^>]*name=["\']__RequestVerificationToken["\']',
            r'name=["\']__RequestVerificationToken["\'][^>]*value=["\']([^"\']{20,})["\']',
        ]
        for pat in patterns:
            m = re.search(pat, html, re.I)
            if m:
                return m.group(1)
        soup = BeautifulSoup(html, 'html.parser')
        inp = soup.find('input', {'name': '__RequestVerificationToken'})
        if inp and inp.get('value'):
            return inp['value']
        return s.cookies.get('__RequestVerificationToken',
                             domain='dining.iut.ac.ir')
    except Exception:
        return None


def api_headers(s, xsrf_token):
    return {
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'en-US,en;q=0.9',
        'Content-Type': 'application/json;charset=UTF-8',
        'Origin': 'https://dining.iut.ac.ir',
        'Referer': 'https://dining.iut.ac.ir/',
        'X-XSRF-Token': xsrf_token,
    }


def check_session_user(s):
    try:
        xsrf = get_real_xsrf_token(s) or ''
        r = s.get("https://dining.iut.ac.ir/api/v1/Student/GetCurrent",
                  headers=api_headers(s, xsrf), timeout=15)
        if r.status_code != 200:
            return None
        data = r.json()
        return str(data.get('Username', '')) or str(data.get('Personneli', ''))
    except Exception:
        return None


def get_credit(s, xsrf):
    try:
        r = s.get("https://dining.iut.ac.ir/api/v0/Credit",
                  headers=api_headers(s, xsrf), timeout=15)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return None


def login_or_load(username, password):
    if os.path.exists(SESSION_FILE):
        s = load_session()
        try:
            v = s.get("https://dining.iut.ac.ir/", allow_redirects=True, timeout=20)
            if 'identity/login' not in v.url and 'signin=' not in v.url:
                actual = check_session_user(s)
                if actual and actual != username:
                    print(f"[!] Session belongs to '{actual}', not '{username}'")
                    os.remove(SESSION_FILE)
                else:
                    print("[OK] Session valid")
                    return s
        except Exception:
            pass
    s = login_via_cas(username, password)
    if s:
        save_session(s)
    return s


def fetch_list(s, meal_id, xsrf):
    url = "https://dining.iut.ac.ir/api/v0/TransferFoodBuy"
    try:
        r = s.get(url, params={'mealId': meal_id, 'status': 123},
                  headers=api_headers(s, xsrf), timeout=15)
        if r.status_code == 429:
            return 'RATE_LIMIT'
        if r.status_code in (401, 403):
            return 'AUTH'
        if r.status_code != 200:
            return None
        data = r.json()
        if isinstance(data, list):
            return data
        return None
    except Exception:
        return None


def buy_once(s, order, xsrf):
    url = "https://dining.iut.ac.ir/api/v0/TransferFoodBuy"
    try:
        r = s.post(url, json=order, headers=api_headers(s, xsrf), timeout=15)
        state_code = None
        state_message = None
        try:
            body = r.json()
            state_code = body.get('StateCode')
            state_message = body.get('StateMessage')
        except Exception:
            pass
        return r.status_code, state_code, state_message, r.text[:200]
    except Exception as e:
        return 0, None, None, str(e)


def buy_phase(s, order, username, password, xsrf):
    print("\n" + "=" * 60)
    print("  PHASE 2: BUY LOOP")
    print(f"  Order: {json.dumps(order, ensure_ascii=False)}")
    print("=" * 60)

    for i in range(1, BUY_MAX_ATTEMPTS + 1):
        status, state_code, state_message, resp = buy_once(s, order, xsrf)
        ts = datetime.now().strftime('%H:%M:%S')

        if status == 200 and state_code == 0:
            print(f"\n  [{ts}] #{i:03d}  [SUCCESS] {state_message}")
            print("\n" + "=" * 60)
            print("  [SUCCESS] PURCHASED!")
            print("=" * 60)
            return True

        elif status == 200 and state_code is not None:
            print(f"  [{ts}] #{i:03d}  [200/Code={state_code}] {state_message}")
            return False

        elif status == 400:
            if i == 1 or i % 5 == 0:
                print(f"  [{ts}] #{i:03d}  [400] - {resp[:80]}")

        elif status == 429:
            print(f"  [{ts}] #{i:03d}  [429] sleeping {BUY_RATE_LIMIT_SLEEP}s")
            time.sleep(BUY_RATE_LIMIT_SLEEP)

        elif status in (401, 403):
            print(f"  [{ts}] #{i:03d}  [{status}] AUTH ERROR - re-login")
            s2 = login_via_cas(username, password)
            if s2:
                save_session(s2)
                s.cookies.clear()
                for c in s2.cookies:
                    s.cookies.set(c.name, c.value,
                                  domain=c.domain, path=c.path)
                xsrf = get_real_xsrf_token(s) or xsrf
            else:
                return False

        else:
            if i == 1 or i % 5 == 0:
                print(f"  [{ts}] #{i:03d}  [{status}] - {resp[:80]}")

        time.sleep(BUY_INTERVAL)

    print(f"\n  [FAIL] Max attempts")
    return False


def sniper(s, meal_name, meal_id, username, password):
    print("\n" + "=" * 60)
    print(f"  SNIPER STARTED")
    print(f"  Meal    : {meal_name} (MealID={meal_id})")
    print(f"  Self    : {CENTRAL_SELF_NAME}")
    print("=" * 60)

    xsrf = get_real_xsrf_token(s)
    if not xsrf:
        print("[!] Could not get XSRF token!")
        return False
    print(f"[OK] XSRF: {xsrf[:50]}...")

    start = time.time()
    max_sec = MAX_RUNTIME_MIN * 60
    attempt = 0

    while time.time() - start < max_sec:
        attempt += 1
        elapsed = int(time.time() - start)
        ts = datetime.now().strftime('%H:%M:%S')

        print(f"\n[{ts}] PHASE 1 - check #{attempt} (elapsed {elapsed}s)")

        result = fetch_list(s, meal_id, xsrf)

        if result == 'RATE_LIMIT':
            print(f"  [!] Rate limited. Sleeping 30s...")
            time.sleep(30)
            continue
        elif result == 'AUTH':
            print(f"  [!] Session expired, re-login")
            s2 = login_via_cas(username, password)
            if s2:
                save_session(s2)
                s = s2
                xsrf = get_real_xsrf_token(s)
            continue
        elif result is None:
            print(f"  [-] Fetch error")
            time.sleep(LIST_POLL_INTERVAL)
            continue
        elif not result:
            print(f"  [-] No food available")
            time.sleep(LIST_POLL_INTERVAL)
            continue

        print(f"  [*] {len(result)} item(s) in list")

        credit = get_credit(s, xsrf)
        if credit is not None:
            print(f"  [*] Credit: {credit}")

        # فقط سلف مرکزی + اعتبار کافی. هیچ فیلتر دیگه‌ای نیست.
        buyable = []
        for f in result:
            if not f.get('FoodID'):
                continue
            if CENTRAL_SELF_NAME not in str(f.get('SelfName', '')):
                continue
            if credit is not None and f.get('BuyPrice', 0) > credit:
                continue
            buyable.append(f)

        if not buyable:
            print(f"  [-] No central food (or all > credit)")
            time.sleep(LIST_POLL_INTERVAL)
            continue

        print(f"  [OK] {len(buyable)} buyable:")
        for f in buyable[:5]:
            print(f"      FoodID={f['FoodID']}  {f['FoodName']}"
                  f"  Date={f['Date']}  Self={f['SelfID']}  Price={f['BuyPrice']}")

        buyable.sort(key=lambda x: x['BuyPrice'])
        chosen = buyable[0]

        order = {
            'Date': chosen['Date'],
            'MealID': chosen['MealID'],
            'FoodID': chosen['FoodID'],
            'SelfID': chosen['SelfID'],
            'BuyPrice': chosen['BuyPrice'],
            'BuyCount': 1,
        }

        print(f"\n  >>> Selected: {chosen['FoodName']} (FoodID={chosen['FoodID']}, Date={chosen['Date']})")

        if buy_phase(s, order, username, password, xsrf):
            return True

        time.sleep(1)

    print("\n[FAIL] Runtime reached")
    return False


def main():
    username, password, meal_name, meal_id = get_inputs()
    s = login_or_load(username, password)
    if not s:
        print("[FAIL] Login failed")
        return
    xsrf = get_real_xsrf_token(s)
    credit = get_credit(s, xsrf) if xsrf else None
    print(f"\n[OK] Credit: {credit}")
    sniper(s, meal_name, meal_id, username, password)


if __name__ == "__main__":
    main()