package atlas.mommy;

final class PanicPages {
    private PanicPages() {
    }

    private static final String STYLE = "<style>"
            + ":root{color-scheme:dark}*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
            + "body{margin:0;min-height:100vh;background:#0b0c10;color:#e8eaf2;font:16px/1.4 system-ui,-apple-system,"
            + "Segoe UI,Roboto,sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "padding:24px;gap:18px;user-select:none}"
            + "h1{font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:#8a91a6;margin:0}"
            + ".big{width:min(78vw,340px);height:min(78vw,340px);border-radius:50%;border:0;color:#fff;font-weight:800;"
            + "font-size:30px;letter-spacing:.02em;background:radial-gradient(circle at 35% 30%,#ff6b6b,#d61f3c 60%,#8e1028);"
            + "box-shadow:0 18px 50px rgba(214,31,60,.45),inset 0 -8px 20px rgba(0,0,0,.25);transition:transform .08s}"
            + ".big:active{transform:scale(.96)}.big.on{background:radial-gradient(circle at 35% 30%,#3a3f52,#232634);"
            + "box-shadow:none;color:#8a91a6}"
            + ".ok{width:min(78vw,340px);height:64px;border-radius:16px;border:1px solid rgba(255,255,255,.12);"
            + "background:#16a34a;color:#fff;font-size:20px;font-weight:700}.ok:disabled{background:#1b1e27;color:#5d6478}"
            + "#st{color:#8a91a6;min-height:22px;text-align:center}#st b{color:#fbbf24}"
            + ".card{background:#12141b;border:1px solid rgba(255,255,255,.08);border-radius:18px;padding:22px;"
            + "max-width:460px;text-align:center}#qr{background:#fff;padding:14px;border-radius:12px;display:inline-block}"
            + "code{word-break:break-all;color:#a5b4fc;font-size:13px}p{color:#b4bacb}"
            + "</style>";

    static String phone() {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<meta name=theme-color content='#0b0c10'><title>MOMMY IS HERE!!!</title>" + STYLE + "</head><body>"
                + "<h1>Atlas remote</h1>"
                + "<button class=big id=fire>MOMMY<br>IS HERE!!!</button>"
                + "<div id=st>Connecting…</div>"
                + "<button class=ok id=gone disabled>She's gone</button>"
                + "<script>"
                + "var k=new URLSearchParams(location.search).get('k')||'';var st=document.getElementById('st');"
                + "var f=document.getElementById('fire'),g=document.getElementById('gone');"
                + "function show(s){f.classList.toggle('on',s.active);g.disabled=!s.active;"
                + "st.innerHTML='';var t=document.createElement('span');"
                + "if(s.active&&s.countdown>=0){t.innerHTML='Hidden. Power step in <b></b> s';"
                + "t.querySelector('b').textContent=Math.ceil(s.countdown/1000);}"
                + "else t.textContent=s.active?'Hidden. Tap \"She\\'s gone\" to bring it back.':'Ready';st.appendChild(t);}"
                + "function call(m,p){return fetch(p,{method:m,headers:{'X-Key':k},cache:'no-store'})"
                + ".then(function(r){if(r.status===401)throw new Error('Wrong key: pair again with .mommy phone');"
                + "if(!r.ok)throw new Error('Error '+r.status);return r.json()}).then(show)"
                + ".catch(function(e){st.textContent=e.message.indexOf('key')>=0?e.message:'Cannot reach the game. Same Wi-Fi? Game open?'})}"
                + "f.onclick=function(){if(navigator.vibrate)navigator.vibrate(60);call('POST','/api/fire')};"
                + "g.onclick=function(){call('POST','/api/restore')};"
                + "call('GET','/api/state');setInterval(function(){call('GET','/api/state')},1500);"
                + "</script></body></html>";
    }

    static String pair(String phoneUrl) {
        String safe = phoneUrl.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&#39;");
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Pair your phone</title>"
                + STYLE + "<script src='/qrcode.js'></script></head><body><div class=card>"
                + "<h1>MOMMY IS HERE!!! · phone remote</h1>"
                + "<p>Scan with your phone's camera (same Wi-Fi as this computer), then add the page to the home screen "
                + "or bookmark it.</p><div id=qr></div>"
                + "<p>Or open on the phone:<br><code id=u>" + safe + "</code></p>"
                + "<p style='font-size:13px;color:#8a91a6'>If the phone cannot reach it, allow Java on private networks "
                + "when Windows Firewall asks. Anyone with this link on your Wi-Fi can press the button: "
                + "<b>.mommy phone reset</b> makes a new one.</p></div>"
                + "<script>new QRCode(document.getElementById('qr'),{text:document.getElementById('u').textContent,"
                + "width:240,height:240,correctLevel:QRCode.CorrectLevel.M});</script></body></html>";
    }
}
