#!/usr/bin/env python3
# usage: python3 fetch.py <url> [max_chars]
# fetch page and extract readable plain text (stdlib only)
import sys, re, urllib.request, gzip, io

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')

def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Accept-Encoding': 'gzip',
    })
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip' or raw[:2] == b'\x1f\x8b':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        for enc in ('utf-8', 'gbk', 'gb18030'):
            try:
                return raw.decode(enc)
            except UnicodeDecodeError:
                continue
        return raw.decode('utf-8', 'ignore')

def main():
    if len(sys.argv) < 2:
        print('usage: fetch.py <url> [max_chars]')
        return
    url = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 6000
    html = fetch(url)
    html = re.sub(r'<script[\s\S]*?</script>|<style[\s\S]*?</style>|<!--[\s\S]*?-->', ' ', html, re.I)
    m = re.search(r'<title[^>]*>([\s\S]*?)</title>', html, re.I)
    title = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(1))).strip() if m else ''
    text = re.sub(r'<[^>]+>', '\n', html)
    text = re.sub(r'&nbsp;', ' ', text)
    text = re.sub(r'&amp;', '&', text)
    text = re.sub(r'&lt;', '<', text)
    text = re.sub(r'&gt;', '>', text)
    lines = [ln.strip() for ln in text.splitlines()]
    lines = [ln for ln in lines if len(ln) > 1]
    body = '\n'.join(lines)
    print('TITLE:', title)
    print('-' * 40)
    print(body[:limit])

if __name__ == '__main__':
    main()
