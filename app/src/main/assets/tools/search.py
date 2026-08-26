#!/usr/bin/env python3
# usage: python3 search.py <query> [count]
# web search via DuckDuckGo HTML endpoint (stdlib only)
import sys, re, urllib.request, urllib.parse

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')

def clean(s):
    s = re.sub(r'<[^>]+>', '', s)
    s = re.sub(r'&amp;', '&', s)
    s = re.sub(r'&#x27;|&#39;', "'", s)
    s = re.sub(r'&quot;', '"', s)
    return re.sub(r'\s+', ' ', s).strip()

def main():
    if len(sys.argv) < 2:
        print('usage: search.py <query> [count]')
        return
    query = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    q = urllib.parse.quote(query)
    url = f'https://html.duckduckgo.com/html/?q={q}'
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    })
    html = urllib.request.urlopen(req, timeout=25).read().decode('utf-8', 'ignore')
    blocks = re.findall(
        r'<a rel="nofollow" class="result__a" href="([^"]+)"[^>]*>([\s\S]*?)</a>',
        html
    )
    snippets = [
        clean(s) for s in re.findall(
            r'class="result__snippet"[^>]*>([\s\S]*?)</a>', html
        )
    ]
    if not blocks:
        print('NO_RESULTS')
        return
    for i, (link, title) in enumerate(blocks[:n]):
        href = urllib.parse.unquote(re.sub(r'^//duckduckgo.com/l/\?uddg=', '', link))
        href = href.split('&rut=')[0]
        snip = snippets[i] if i < len(snippets) else ''
        print(f'{i + 1}. {clean(title)}')
        print(f'   URL: {href}')
        if snip:
            print(f'   {snip}')
        print()

if __name__ == '__main__':
    main()
