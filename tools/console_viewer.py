"""Virtual Console 화면을 '보기 전용'으로 띄우는 로컬 프록시.

왜 필요한가
  콘솔 페이지는 자체 물리 계산(physicsTick)을 매 프레임 돌리고, 차가 움직이는 동안
  자기가 계산한 속도를 PUT /vehicle/controls로 서버에 계속 써 넣는다.
  그래서 페이지를 열어 둔 채 스크립트(drive_scenario.py)로 속도를 넣으면 둘이 서로 덮어써서 속도가 튄다.
  무드등 색은 이 페이지에만 보이므로 녹화하려면 페이지를 열어야 한다.

해결
  이 프록시는 127.0.0.1:8088의 콘솔을 그대로 중계하면서, 첫 화면 HTML 끝에 작은 스크립트를 넣어
  페이지의 물리 계산과 속도 쓰기를 끈다. 페이지는 서버 상태(/vehicle/state)만 0.2초마다 읽어 그린다.
  (무드등 색, 공조, 좌석, 속도, 핸들 표시는 그대로 보인다. 페이지의 ACCEL, BRAKE 패드는 동작하지 않는다.)

사용
  adb forward tcp:8088 tcp:8088
  python tools/console_viewer.py          # 브라우저로 http://127.0.0.1:8090/ 을 연다
  python tools/drive_scenario.py          # 다른 창에서 시연 주행
"""
import http.server
import socketserver
import sys
import urllib.error
import urllib.request

UPSTREAM = "http://127.0.0.1:8088"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090

VIEWER_JS = b"""
<script>
/* console_viewer.py: page physics off, draw server state only */
(function(){
  try{
    markLocalControl=function(){};
    queueCommit=function(){};
    physicsTick=function(now){lastFrame=now;renderAll();requestAnimationFrame(physicsTick);};
    setInterval(refresh,200);
    document.title="Virtual Console (viewer)";
    console.log("[viewer] page physics disabled");
  }catch(e){console.error("[viewer] inject failed",e);}
})();
</script>
"""

HOP_HEADERS = {"host", "connection", "keep-alive", "transfer-encoding", "content-length",
               "accept-encoding", "content-encoding", "proxy-connection", "upgrade"}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _proxy(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
        req = urllib.request.Request(UPSTREAM + self.path, data=body, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                status, rh, data = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, rh, data = e.code, e.headers, e.read()
        except Exception as e:  # noqa: BLE001
            self.send_error(502, f"upstream: {e}")
            return
        ctype = rh.get("Content-Type", "")
        if self.command == "GET" and self.path.split("?")[0] in ("/", "/index.html") and "html" in ctype:
            i = data.rfind(b"</body>")
            data = data[:i] + VIEWER_JS + data[i:] if i >= 0 else data + VIEWER_JS
        self.send_response(status)
        for k, v in rh.items():
            if k.lower() not in HOP_HEADERS:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = _proxy

    def log_message(self, fmt, *args):  # 요청 로그는 끈다(하트비트가 1초마다 온다)
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    print(f"viewer: http://127.0.0.1:{PORT}/  (upstream {UPSTREAM})", flush=True)
    Server(("127.0.0.1", PORT), Handler).serve_forever()
