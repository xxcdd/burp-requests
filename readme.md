# burp-requests
## refer
https://github.com/silentsignal/burp-requests
## add new
- pretty code out put
- ssl error ignore
```python
import requests
from requests.packages import urllib3
urllib3.disable_warnings()
proxies = {}
# proxies = {"http": "http://127.0.0.1:8080", "https": "http://127.0.0.1:8080"}

burp0_url = "https://www.google.com:443/"
burp0_headers = {
    "Connection": "close",
    "Sec-Fetch-Site": "cross-site",
    "Sec-Fetch-Mode": "no-cors",
    "Sec-Fetch-Dest": "empty",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.66 Safari/537.36",
    "Accept-Encoding": "gzip, deflate",
    "Accept-Language": "zh-CN,zh;q=0.9"
}
resp = requests.get(burp0_url, headers=burp0_headers, proxies=proxies, verify=False)
```