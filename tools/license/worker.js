/**
 * Minimal Drive — сервис активации (Cloudflare Worker).
 *
 * Держит приватный ключ (секрет PRIVATE_KEY_PKCS8) и выдаёт коды активации только тем магнитолам,
 * которые владелец одобрил. Магнитола проверяет код своим встроенным ПУБЛИЧНЫМ ключом офлайн,
 * поэтому даже утечка Worker'а не даёт подделать код без приватного ключа.
 *
 * Секреты (wrangler secret put ...):
 *   PRIVATE_KEY_PKCS8 — приватный ключ RSA в PKCS8 PEM (…-secret/private_pkcs8.pem)
 *   ADMIN_PASS        — пароль страницы одобрения
 * Привязка KV: DEVICES (список одобренных магнитол).
 *
 * Эндпойнты:
 *   GET  /                — страница владельца (с телефона): ввести код магнитолы → «Активировать»
 *   POST /admin/approve   — {pass, device} → одобрить и вернуть код активации
 *   POST /admin/revoke    — {pass, device} → отозвать
 *   POST /admin/list      — {pass}         → список одобренных
 *   GET  /check?device=…  — магнитола сама забирает свой код, если одобрена
 */

const enc = new TextEncoder();

async function importKey(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", der.buffer, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

async function sign(env, device) {
  const key = await importKey(env.PRIVATE_KEY_PKCS8);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(device));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

function clean(d) {
  return (d || "").toUpperCase().replace(/[^0-9A-F]/g, "").slice(0, 12);
}

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);

    if (url.pathname === "/check") {
      const device = clean(url.searchParams.get("device"));
      if (device.length !== 12) return json({ error: "bad device" }, 400);
      const approved = await env.DEVICES.get(device);
      if (!approved) return json({ error: "not approved" }, 404);
      return json({ sig: await sign(env, device) });
    }

    if (req.method === "POST" && url.pathname.startsWith("/admin/")) {
      const body = await req.json().catch(() => ({}));
      if (body.pass !== env.ADMIN_PASS) return json({ error: "wrong password" }, 403);

      if (url.pathname === "/admin/approve") {
        const device = clean(body.device);
        if (device.length !== 12) return json({ error: "код магнитолы должен быть 12 символов (0-9, A-F)" }, 400);
        await env.DEVICES.put(device, new Date().toISOString());
        return json({ device, sig: await sign(env, device) });
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
  .wrap{max-width:520px;margin:0 auto}
  h1{font-size:22px;margin:8px 0 16px}
  input{width:100%;padding:14px;border-radius:12px;border:1px solid var(--stroke);background:#17191b;color:#fff;font-size:18px;margin:6px 0;font-family:ui-monospace,monospace;letter-spacing:1px}
  button{width:100%;padding:14px;border-radius:12px;border:0;background:var(--acc);color:#15171a;font-size:17px;font-weight:600;margin-top:8px}
  button.sec{background:#1b1e21;color:#c9ccce;border:1px solid var(--stroke)}
  .card{background:var(--card);border:1px solid var(--stroke);border-radius:16px;padding:20px;margin-top:14px}
  .muted{color:var(--muted);font-size:14px}
  .code{font-family:ui-monospace,monospace;font-size:13px;word-break:break-all;background:#17191b;padding:12px;border-radius:10px;border:1px solid var(--stroke);margin-top:8px}
  .ok{color:#5cd14a}.err{color:#f0342a}
  .row{display:flex;gap:8px}.row button{margin-top:0}
  .dev{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-top:1px solid var(--stroke)}
</style>
<div class=wrap>
  <h1>Активация магнитолы</h1>
  <div class=card>
    <div class=muted>Пароль (сохранится на этом телефоне)</div>
    <input id=pass type=password placeholder=Пароль>
    <div class=muted style=margin-top:12px>Код магнитолы (с её экрана блокировки)</div>
    <input id=dev placeholder="8B46-EA8A-08F4" autocapitalize=characters>
    <button onclick=approve()>Активировать</button>
    <div class=row style=margin-top:8px>
      <button class=sec onclick=revoke()>Отозвать</button>
      <button class=sec onclick=list()>Список</button>
    </div>
    <div id=out></div>
  </div>
  <p class=muted>После «Активировать» магнитола активируется сама при следующем запуске (нужен интернет один раз).
  Если она офлайн — скопируйте код активации ниже и вставьте его на магнитоле кнопкой «Вставить код активации».</p>
</div>
<script>
  const $=id=>document.getElementById(id);
  pass.value=localStorage.getItem('mdpass')||'';
  const post=(p,b)=>fetch(p,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)}).then(r=>r.json());
  function save(){localStorage.setItem('mdpass',pass.value)}
  async function approve(){save();out.innerHTML='…';const r=await post('/admin/approve',{pass:pass.value,device:dev.value});
    out.innerHTML=r.error?'<p class=err>'+r.error+'</p>':
      '<p class=ok>✓ '+r.device+' активирована</p><div class=muted>Код активации (если магнитола офлайн — вставьте вручную):</div><div class=code>'+r.sig+'</div>';}
  async function revoke(){save();out.innerHTML='…';const r=await post('/admin/revoke',{pass:pass.value,device:dev.value});
    out.innerHTML=r.error?'<p class=err>'+r.error+'</p>':'<p class=ok>Отозвано</p>';}
  async function list(){save();out.innerHTML='…';const r=await post('/admin/list',{pass:pass.value});
    if(r.error){out.innerHTML='<p class=err>'+r.error+'</p>';return}
    out.innerHTML='<div class=muted>Активировано: '+r.devices.length+'</div>'+r.devices.map(d=>'<div class=dev><span>'+d+'</span><button class=sec style="width:auto;padding:6px 12px" onclick="dev.value=\\''+d+'\\'">выбрать</button></div>').join('');}
</script>`;
