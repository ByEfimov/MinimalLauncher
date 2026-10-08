// Minimal Drive — прокси на Yandex Cloud Functions (белый список РФ).
// GET  ?action=weather&lat=&lon=   → JSON Open-Meteo
// GET  ?action=get&url=<encoded>   → GET к разрешённому хосту (поиск/маршруты OSM: Photon, Nominatim, OSRM)
// POST ?action=overpass (тело data=<запрос>) → JSON Overpass (ограничения скорости, камеры)
// Среда: Node.js 18+ (глобальный fetch). Точка входа: index.handler. Функция публичная.

// Разрешённые хосты для action=get (чтобы функция не была открытым прокси).
const GET_HOSTS = [
  "photon.komoot.io",
  "nominatim.openstreetmap.org",
  "router.project-osrm.org",
  "routing.openstreetmap.de",
  "api.open-meteo.com",
];

async function passthrough(url, init) {
  const r = await fetch(url, init);
  const body = await r.text();
  return { statusCode: r.status, headers: { "Content-Type": "application/json; charset=utf-8" }, body };
}

async function fetchTimeout(url, init, ms) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), ms);
  try { return await fetch(url, { ...init, signal: ctrl.signal }); }
  finally { clearTimeout(t); }
}

exports.handler = async (event) => {
  const q = (event && event.queryStringParameters) || {};
  const action = q.action || "";
  try {
    if (action === "weather") {
      const lat = encodeURIComponent(q.lat || "0"), lon = encodeURIComponent(q.lon || "0");
      const url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
        "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=24" +
        "&daily=temperature_2m_min,temperature_2m_max,weather_code,precipitation_sum&forecast_days=6" +
        "&wind_speed_unit=ms&timezone=auto";
      return await passthrough(url);
    }
    if (action === "get") {
      let target;
      try { target = new URL(q.url || ""); } catch (e) { return { statusCode: 400, headers: { "Content-Type": "text/plain" }, body: "bad url" }; }
      if (target.protocol !== "https:" || !GET_HOSTS.includes(target.hostname))
        return { statusCode: 403, headers: { "Content-Type": "text/plain" }, body: "host not allowed" };
      try {
        const r = await fetchTimeout(target.toString(), { headers: {
          "User-Agent": "MinimalDrive/1.0 (+github.com/ByEfimov/MinimalLauncher)",
          "Accept": "application/json",
          "Accept-Language": "ru",
        } }, 15000);
        const body = await r.text();
        return { statusCode: r.status, headers: { "Content-Type": "application/json; charset=utf-8" }, body };
      } catch (e) {
        return { statusCode: 504, headers: { "Content-Type": "text/plain" }, body: String((e && e.message) || e) };
      }
    }
    if (action === "overpass") {
      let payload = event.body || "";
      if (event.isBase64Encoded) payload = Buffer.from(payload, "base64").toString("utf8");
      // быстрые/надёжные зеркала первыми; у каждого свой таймаут, чтобы один медленный не съел весь бюджет
      const mirrors = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
      ];
      const headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json",
        "User-Agent": "MinimalDrive/1.0 (+github.com/ByEfimov/MinimalLauncher)",
      };
      let last = { statusCode: 502, headers: { "Content-Type": "text/plain" }, body: "overpass unavailable" };
      for (const m of mirrors) {
        try {
          const r = await fetchTimeout(m, { method: "POST", headers, body: payload }, 6000);
          const body = await r.text();
          if (r.status === 200) return { statusCode: 200, headers: { "Content-Type": "application/json; charset=utf-8" }, body };
          last = { statusCode: r.status, headers: { "Content-Type": "text/plain" }, body: String(body).slice(0, 200) };
        } catch (e) { last = { statusCode: 504, headers: { "Content-Type": "text/plain" }, body: String((e && e.message) || e) }; }
      }
      return last;
    }
    return { statusCode: 400, headers: { "Content-Type": "text/plain" }, body: "unknown action" };
  } catch (e) {
    return { statusCode: 502, headers: { "Content-Type": "text/plain" }, body: "proxy error: " + ((e && e.message) ? e.message : String(e)) };
  }
};
