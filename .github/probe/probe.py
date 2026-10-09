import json, re, time, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14)"
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "fr-FR", "Cache-Control": "no-cache"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            b = r.read().decode("utf-8", "replace"); print(f"  GET {url} -> {r.status} {len(b)}B {time.time()-t:.1f}s"); return b
    except Exception as e:
        print(f"  GET {url} -> ERR {e} {time.time()-t:.1f}s"); return ""
def apple(cid):
    html = get(f"https://podcasts.apple.com/fr/podcast/id{cid}")
    m = html.find('id="serialized-server-data"'); s = html.index('>', m)+1; e = html.index('</script>', s)
    out = set()
    def walk(x):
        if isinstance(x, dict):
            enc = None
            if isinstance(x.get("currentMediaEnclosure"), dict): enc = x["currentMediaEnclosure"].get("streamUrl")
            if not enc and isinstance(x.get("mediaEnclosures"), list):
                for en in x["mediaEnclosures"]:
                    if isinstance(en, dict) and en.get("streamUrl"): enc = en["streamUrl"]; break
            if x.get("title") and x.get("releaseDate") and enc: out.add((x["releaseDate"], x["title"], enc))
            for v in x.values(): walk(v)
        elif isinstance(x, list):
            for v in x: walk(v)
    walk(json.loads(html[s:e])); return sorted(out, reverse=True)
fname = lambda u: u.split("?")[0].rstrip("/").split("/")[-1]
print("NOW UTC", time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
shows = {"edito eco": 115147336, "edito eco VSD": 6807051676, "edito politique": 306757988, "edito politique VSD": 6806987980,
         "jeu 1000": 306769782, "journal 8h": 541446017, "journal 8h WE": 1409253290, "journal 6h": 550488010}
for name, cid in shows.items():
    print(f"\n=== {name} ({cid})")
    eps = apple(cid)
    for d, t, u in eps[:2]: print("   apple", d, "|", t[:50], "|", fname(u)[:60])
    uuids = []
    for d, t, u in eps:
        p = u.split("?")[0].split("/")
        if "proxycast.radiofrance.fr" in u and len(p) >= 7: uuids.append(p[4])
    from collections import Counter
    print("   uuid counts:", Counter(uuids).most_common(3))
    if not uuids: continue
    uuid = Counter(uuids).most_common(1)[0][0]
    xml = get(f"https://radiofrance-podcast.net/podcast09/podcast_{uuid}.xml")
    ch = re.search(r"<title>(.*?)</title>", xml, re.S)
    items = re.findall(r"<item>(.*?)</item>", xml, re.S)
    print("   RSS title:", ch and ch.group(1)[:70], "| items", len(items))
    rssfiles = set()
    for it in items[:3]:
        t = re.search(r"<title>(.*?)</title>", it, re.S); d = re.search(r"<pubDate>(.*?)</pubDate>", it); u = re.search(r'enclosure[^>]+url="([^"]+)"', it)
        print("    -", d and d.group(1), "|", t and t.group(1)[:55], "|", u and u.group(1)[:140])
    for it in items:
        u = re.search(r'enclosure[^>]+url="([^"]+)"', it)
        if u: rssfiles.add(fname(u.group(1)))
    applefiles = {fname(u) for _, _, u in eps}
    print("   common filenames apple/rss:", len(applefiles & rssfiles), "of", len(applefiles))
    hdr = re.search(r"<itunes:new-feed-url>(.*?)<", xml); print("   new-feed-url:", hdr and hdr.group(1))
