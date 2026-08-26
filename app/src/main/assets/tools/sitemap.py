#!/usr/bin/env python3
# usage: python3 sitemap.py <site_base_url> [limit]
# discover sitemap via robots.txt and list site links (stdlib only)
import sys, re, urllib.request

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')

def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    return urllib.request.urlopen(req, timeout=20).read().decode('utf-8', 'ignore')

def main():
    if len(sys.argv) < 2:
        print('usage: sitemap.py <site_base_url> [limit]')
        return
    base = sys.argv[1].rstrip('/')
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    maps = []
    try:
        robots = get(base + '/robots.txt')
        maps = re.findall(r'^Sitemap:\s*(\S+)', robots, re.M | re.I)
    except Exception:
        pass
    if not maps:
        maps = [base + '/sitemap.xml']
    seen = []
    for sm in maps[:3]:
        try:
            xml = get(sm)
        except Exception as e:
            print(f'# sitemap {sm} failed: {e}')
            continue
        locs = re.findall(r'<loc>\s*([^<]+?)\s*</loc>', xml)
        for loc in locs:
            if loc.endswith('.xml'):
                continue
            if loc not in seen:
                seen.append(loc)
                if len(seen) >= n:
                    break
        if len(seen) >= n:
            break
    for u in seen:
        print(u)

if __name__ == '__main__':
    main()
