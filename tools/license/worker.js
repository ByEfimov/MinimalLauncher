/**
 * Minimal Drive — сервис активации (Cloudflare Worker), 6-значные коды.
 *
 * Держит секрет HMAC (SECRET_HEX) и выдаёт 6-значные коды активации только одобренным магнитолам.
 * Магнитола проверяет код тем же секретом (вшит в приложение) офлайн.
 *
 * Секреты (wrangler secret put ...):
 *   SECRET_HEX  — тот же секрет, что в приложении (…-secret/hmac_secret.hex)
 *   ADMIN_PASS  — пароль страницы одобрения
 * Привязка KV: DEVICES (одобренные магнитолы).
 *
 *   GET  /                — страница владельца (с телефона)
 *   POST /admin/approve   — {pass, device} → одобрить и вернуть 6-значный код
 *   POST /admin/revoke    — {pass, device}
 *   POST /admin/list      — {pass}
 *   GET  /check?device=…  — магнитола сама забирает код, если одобрена
 */

const enc = new TextEncoder();

async function code(env, device) {
  const keyBytes = Uint8Array.from((env.SECRET_HEX.match(/.{2}/g) || []).map((h) => parseInt(h, 16)));
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(device)));
  let n = 0n;
  for (let i = 0; i < 8; i++) n = (n << 8n) | BigInt(mac[i]);
  return String(n % 1000000n).padStart(6, "0");
}

const clean = (d) => (d || "").replace(/[^0-9]/g, "").slice(0, 6);
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);

    if (url.pathname === "/check") {
      const device = clean(url.searchParams.get("device"));
      if (device.length !== 6) return json({ error: "bad device" }, 400);
      if (!(await env.DEVICES.get(device))) return json({ error: "not approved" }, 404);
      return json({ code: await code(env, device) });
    }

    if (req.method === "POST" && url.pathname.startsWith("/admin/")) {
      const body = await req.json().catch(() => ({}));
      if (body.pass !== env.ADMIN_PASS) return json({ error: "wrong password" }, 403);

      if (url.pathname === "/admin/approve") {
        const device = clean(body.device);
        if (device.length !== 6) return json({ error: "код магнитолы — 6 цифр" }, 400);
        await env.DEVICES.put(device, new Date().toISOString());
        return json({ device, code: await code(env, device) });
      }
      if (url.pathname === "/admin/revoke") {
        await env.DEVICES.delete(clean(body.device));
        return json({ ok: true });
      }
      if (url.pathname === "/admin/list") {
        const list = await env.DEVICES.list();
        return json({ devices: list.keys.map((k) => k.name) });
      }
    }

    if (url.pathname === "/") return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8" } });
    return new Response("Not found", { status: 404 });
  },
};

const PAGE = `<!doctype html><html lang=ru><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Minimal Drive — активация</title>
<style>
  :root{--bg:#0c0e0f;--card:#141618;--stroke:#26292c;--text:#fff;--muted:#8c9195;--acc:#ffd23a}
  *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.4 -apple-system,system-ui,sans-serif;padding:20px}
  .wrap{max-width:520px;margin:0 auto}h1{font-size:22px;margin:8px 0 16px}
  input{width:100%;padding:14px;border-radius:12px;border:1px solid var(--stroke);background:#17191b;color:#fff;font-size:20px;margin:6px 0;font-family:ui-monospace,monospace;letter-spacing:2px}
  button{width:100%;padding:14px;border-radius:12px;border:0;background:var(--acc);color:#15171a;font-size:17px;font-weight:600;margin-top:8px}
  button.sec{background:#1b1e21;color:#c9ccce;border:1px solid var(--stroke)}
  .card{background:var(--card);border:1px solid var(--stroke);border-radius:16px;padding:20px;margin-top:14px}
  .muted{color:var(--muted);font-size:14px}
  .big{font-family:ui-monospace,monospace;font-size:44px;font-weight:600;letter-spacing:6px;text-align:center;margin:10px 0}
  .ok{color:#5cd14a}.err{color:#f0342a}
  .row{display:flex;gap:8px}.row button{margin-top:0}
  .dev{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-top:1px solid var(--stroke)}
</style>
<div class=wrap>
  <h1>Активация магнитолы</h1>
  <div class=card>
    <div class=muted>Пароль (сохранится на этом телефоне)</div>
    <input id=pass type=password placeholder=Пароль>
    <div class=muted style=margin-top:12px>Код магнитолы (6 цифр с её экрана)</div>
    <input id=dev inputmode=numeric placeholder="373039" maxlength=6>
    <button onclick=approve()>Активировать</button>
    <div class=row style=margin-top:8px>
      <button class=sec onclick=revoke()>Отозвать</button>
      <button class=sec onclick=list()>Список</button>
    </div>
    <div id=out></div>
  </div>
  <p class=muted>После «Активировать» магнитола активируется сама (нужен интернет один раз).
  Если офлайн — продиктуйте показанный код активации, на магнитоле введите его вручную.</p>
</div>
<script>
  pass.value=localStorage.getItem('mdpass')||'';
  const post=(p,b)=>fetch(p,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)}).then(r=>r.json());
  const save=()=>localStorage.setItem('mdpass',pass.value);
  async function approve(){save();out.innerHTML='…';const r=await post('/admin/approve',{pass:pass.value,device:dev.value});
    out.innerHTML=r.error?'<p class=err>'+r.error+'</p>':
      '<p class=ok>✓ '+r.device+' активирована</p><div class=muted>Код активации (если магнитола офлайн — введите вручную):</div><div class=big>'+r.code+'</div>';}
  async function revoke(){save();out.innerHTML='…';const r=await post('/admin/revoke',{pass:pass.value,device:dev.value});
    out.innerHTML=r.error?'<p class=err>'+r.error+'</p>':'<p class=ok>Отозвано</p>';}
  async function list(){save();out.innerHTML='…';const r=await post('/admin/list',{pass:pass.value});
    if(r.error){out.innerHTML='<p class=err>'+r.error+'</p>';return}
    out.innerHTML='<div class=muted>Активировано: '+r.devices.length+'</div>'+r.devices.map(d=>'<div class=dev><span>'+d+'</span><button class=sec style="width:auto;padding:6px 12px" onclick="dev.value=\\''+d+'\\'">выбрать</button></div>').join('');}
</script>`;
