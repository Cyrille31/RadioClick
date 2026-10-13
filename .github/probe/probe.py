import re, time, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14)"
def req(url, method="GET"):
    r = urllib.request.Request(url, method=method, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, (resp.read().decode("utf-8", "replace") if method == "GET" else "")
    except urllib.error.HTTPError as e: return e.code, ""
    except Exception as e: return str(e), ""
print("NOW UTC", time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
for station in ["franceinter", "franceinfo", "franceculture"]:
    url = f"https://stream.radiofrance.fr/{station}/{station}_hifi.m3u8?id=radiofrance"
    st, txt = req(url)
    print(f"\n=== {station} {st}")
    print("\n".join(txt.splitlines()[:14]))
    segs = [l for l in txt.splitlines() if l and not l.startswith("#")]
    if not segs: continue
    last = segs[-1]
    full = last if last.startswith("http") else "https://stream.radiofrance.fr" + last
    m = re.search(r"^(.*_)(\d+)_(\d+)(\.\w+.*)$", full)
    pre, seq, stamp, suf = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
    print("last", full)
    for back_min in [10, 30, 60, 120, 180, 360, 600, 720, 1080, 1440, 2880]:
        k = back_min * 60 // 4
        u = f"{pre}{seq-k}_{stamp-4*k}{suf}"
        s1, _ = req(u, "HEAD")
        print(f"  -{back_min:5d} min -> {s1}")
for show in ["72410411-a76f-4c36-99f5-3918fbc24bb8", "6f03e972-cada-490c-a455-cb8223327109", "48f74c6a-eb98-11e1-a7b7-782bcb76618d"]:
    st, xml = req(f"https://radiofrance-podcast.net/podcast09/podcast_{show}.xml")
    ch = xml.split("<item>")[0]
    print("\n=== feed", show, st)
    for tag in ["title", "itunes:author", "author", "managingEditor", "itunes:owner", "copyright", "link"]:
        mm = re.search(rf"<{tag}>(.*?)</{tag}>", ch, re.S); print(f"  {tag}: {mm and mm.group(1)[:120]}")
    for it in xml.split("<item>")[1:8]:
        d = re.search(r"<pubDate>(.*?)</pubDate>", it); du = re.search(r"<itunes:duration>(.*?)</itunes:duration>", it)
        print("   ", d and d.group(1), "| dur", du and du.group(1))
