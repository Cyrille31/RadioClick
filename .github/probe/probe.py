import json, re, sys, time, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Linux; Android 14)"
def get(url, ua=UA):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Language": "fr-FR"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read().decode("utf-8", "replace")
            print(f"  GET {url} -> {r.status} {len(body)}B {time.time()-t:.1f}s final={r.geturl()}")
            return body
    except Exception as e:
        print(f"  GET {url} -> ERR {e} {time.time()-t:.1f}s")
        return ""

def apple_eps(html):
    m = html.find('id="serialized-server-data"')
    if m < 0: return [], None
    s = html.index('>', m) + 1; e = html.index('</script>', s)
    root = json.loads(html[s:e]); out = []; urls = set()
    def walk(x):
        if isinstance(x, dict):
            t, d = x.get("title"), x.get("releaseDate")
            enc = (x.get("currentMediaEnclosure") or {}).get("streamUrl") if isinstance(x.get("currentMediaEnclosure"), dict) else None
            if not enc and isinstance(x.get("mediaEnclosures"), list):
                for en in x["mediaEnclosures"]:
                    if isinstance(en, dict) and en.get("streamUrl"): enc = en["streamUrl"]; break
            if t and d and enc: out.append((d, t, enc))
            for k, v in x.items():
                if isinstance(v, str) and "radiofrance.fr" in v and v.startswith("http"): urls.add((k, v))
                walk(v)
        elif isinstance(x, list):
            for v in x: walk(v)
    walk(root)
    return sorted(set(out), reverse=True), urls

def rss_items(xml, n=4):
    title = re.search(r"<title>(.*?)</title>", xml, re.S)
    items = re.findall(r"<item>(.*?)</item>", xml, re.S)[:n]
    print("    RSS channel:", title.group(1)[:80] if title else None, "items:", len(re.findall(r"<item>", xml)))
    for it in items:
        t = re.search(r"<title>(.*?)</title>", it, re.S); d = re.search(r"<pubDate>(.*?)</pubDate>", it); u = re.search(r'enclosure[^>]+url="([^"]+)"', it)
        print("     -", d and d.group(1), "|", t and t.group(1)[:70], "|", u and u.group(1))

print("NOW UTC", time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
for term in ["edito eco france inter", "edito politique france inter", "jeu des 1000 euros", "journal 8h france inter", "chronique classique radio classique"]:
    print("\n=== SEARCH", term)
    body = get("https://itunes.apple.com/search?" + urllib.parse.urlencode({"term": term, "media": "podcast", "country": "fr", "limit": 6}))
    try: res = json.loads(body)["results"]
    except Exception: res = []
    for r in res:
        print(f"  * {r.get('collectionId')} | {r.get('collectionName')} | {r.get('artistName')} | feed={r.get('feedUrl')} | web={r.get('collectionViewUrl','')[:60]}")
    for r in res[:2]:
        cid = r.get("collectionId")
        print(f"  -- Apple page {cid} {r.get('collectionName')}")
        html = get(f"https://podcasts.apple.com/fr/podcast/id{cid}")
        eps, urls = apple_eps(html)
        for d, t, enc in eps[:4]: print("     ep", d, "|", t[:60], "|", enc)
        print("     rf urls in json:", list(urls)[:6])
        lk = get(f"https://itunes.apple.com/lookup?id={cid}&entity=podcastEpisode&limit=4&country=fr")
        try:
            for x in json.loads(lk)["results"][:4]:
                print("     lookup", x.get("wrapperType"), x.get("releaseDate"), "|", (x.get("trackName") or x.get("collectionName") or "")[:60], "|", x.get("episodeUrl"), "| feed", x.get("feedUrl"))
        except Exception as e: print("     lookup err", e)
        for d, t, enc in eps[:1]:
            m = re.search(r"/(\d+)-\d\d\.\d\d\.\d{4}-", enc)
            if m:
                x = get(f"https://radiofrance-podcast.net/podcast09/rss_{m.group(1)}.xml")
                if x: rss_items(x, 2)
        for k, v in list(urls)[:2]:
            page = get(v)
            for mm in sorted(set(re.findall(r'https?://[^"\'\s<>]*(?:rss|podcast09)[^"\'\s<>]*', page)))[:8]: print("       link", mm)
