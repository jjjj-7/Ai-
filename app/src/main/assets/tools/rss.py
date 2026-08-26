#!/usr/bin/env python3
# usage: python3 rss.py <feed_url> [count]
# parse RSS/Atom feed (stdlib only)
import sys, re, urllib.request

def pick(block, tag):
    m = re.search(rf'<{tag}[^>]*>([\s\S]*?)</{tag}>', block)
    if not m:
        return ''
    s = m.group(1).strip()
    s = re.sub(r'<!\[CDATA\[|\]\]>', '', s)
    s = re.sub(r'<[^>]+>', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()

def main():
    if len(sys.argv) < 2:
        print('usage: rss.py <feed_url> [count]')
        return
    url = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    req = urllib.request.Request(url, headers={
        'User-Agent': ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                       'AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36')
    })
    xml = urllib.request.urlopen(req, timeout=25).read().decode('utf-8', 'ignore')
    blocks = re.findall(r'<item[\s\S]*?</item>|<entry[\s\S]*?</entry>', xml)
    if not blocks:
        print('NO_ITEMS')
        return
    for i, b in enumerate(blocks[:n]):
        title = pick(b, 'title')
        link = pick(b, 'link') or ''
        date = pick(b, 'pubDate') or pick(b, 'updated') or pick(b, 'published')
        desc = pick(b, 'description') or pick(b, 'summary')
        print(f'{i + 1}. {title}')
        if link:
            print(f'   LINK: {link}')
        if date:
            print(f'   DATE: {date}')
        if desc:
            print(f'   {desc[:200]}')
        print()

if __name__ == '__main__':
    main()
