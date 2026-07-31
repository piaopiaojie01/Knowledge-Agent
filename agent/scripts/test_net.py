import requests, time

def test(url, name):
    try:
        t0 = time.time()
        r = requests.get(url, timeout=4, headers={'User-Agent': 'Mozilla/5.0'})
        print(f'{name}: OK ({time.time()-t0:.1f}s) status={r.status_code}')
    except Exception as e:
        print(f'{name}: FAILED ({time.time()-t0:.1f}s) - {type(e).__name__}')

test('https://api.duckduckgo.com/?q=test&format=json&no_html=1', 'DDG API')
test('https://duckduckgo.com/?q=test', 'DDG Web')
test('https://www.baidu.com/s?wd=test', 'Baidu')
