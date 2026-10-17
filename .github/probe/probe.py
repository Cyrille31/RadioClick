import json, re, time, urllib.request, urllib.parse
from collections import Counter
UA = "Mozilla/5.0 (Linux; Android 14)"
def get(url):
    r = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "fr-FR"})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp: return resp.read().decode("utf-8", "replace")
    except Exception as e: print("  ERR", url, e); return ""
print("NOW UTC", time.strftime("%Y-%m-%d %H:%M:%S %a", time.gmtime()))
seen = {}
for term in ["journal 13h France Inter", "journal de 13h", "journal 13h week-end France Inter", "journal France Inter week-end"]:
    res = json.loads(get("https://itunes.apple.com/search?" + urllib.parse.urlencode({"term": term, "media": "podcast", "entity": "podcast", "country": "FR", "limit": 40})) or '{"results":[]}')["results"]
    print(f"\n=== search {term!r}")
    for r in res:
        if "inter" in (r.get("artistName") or "").lower() and "journal" in (r.get("collectionName") or "").lower():
            print(f"  {r['collectionId']} | {r['collectionName']} | {r['artistName']}")
            seen[r["collectionId"]] = r["collectionName"]
for cid, name in seen.items():
    if "13" not in name: continue
    html = get(f"https://podcasts.apple.com/fr/podcast/id{cid}")
    m = html.find('id="serialized-server-data"')
    if m < 0: continue
    s = html.index('>', m) + 1; e = html.index('</script>', s)
    urls = re.findall(r'https://proxycast\.radiofrance\.fr/[^"\\]+', html[s:e])
    shows = Counter(u.split("/")[4] for u in urls if len(u.split("/")) > 6)
    print(f"\n=== {cid} {name} uuid={shows.most_common(1)}")
    if not shows: continue
    xml = get(f"https://radiofrance-podcast.net/podcast09/podcast_{shows.most_common(1)[0][0]}.xml")
    a = re.search(r"<itunes:author>(.*?)</itunes:author>", xml.split("<item>")[0])
    print("   author:", a and a.group(1))
    for it in xml.split("<item>")[1:12]:
        d = re.search(r"<pubDate>(.*?)</pubDate>", it); du = re.search(r"<itunes:duration>(.*?)<", it); t = re.search(r"<title>(.*?)</title>", it, re.S)
        print("   ", d and d.group(1), "|", du and du.group(1), "|", t and t.group(1)[:50])
