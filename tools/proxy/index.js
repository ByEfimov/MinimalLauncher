// Minimal Drive — прокси на Yandex Cloud Functions (белый список РФ).
// Магнитола обращается только к *.yandexcloud.net; функция сама ходит за погодой и ограничениями
// скорости в открытые источники (их egress с сервера не режется) и отдаёт готовый JSON.
//
// Вызовы:
//   GET  ?action=weather&lat=..&lon=..   → JSON Open-Meteo (погода: сейчас/по часам/на 6 дней)
//   POST ?action=overpass  (тело data=<запрос>) → JSON Overpass (ограничения скорости, камеры)
//
// Среда: Node.js 18 (есть глобальный fetch). Точка входа: index.handler. Функция публичная.

async function passthrough(url, init) {
  const r = await fetch(url, init);
  const body = await r.text();
  return { statusCode: r.status, headers: { "Content-Type": "application/json; charset=utf-8" }, body };
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
    if (action === "overpass") {
      let payload = event.body || "";
      if (event.isBase64Encoded) payload = Buffer.from(payload, "base64").toString("utf8");
      // тело уже в виде data=<urlencoded>, как отправляет магнитола. Перебираем зеркала до первого рабочего.
      const mirrors = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
      ];
      let last = { statusCode: 502, headers: { "Content-Type": "text/plain" }, body: "overpass unavailable" };
      for (const m of mirrors) {
        try {
          const r = await fetch(m, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: payload });
          const body = await r.text();
          if (r.status === 200) return { statusCode: 200, headers: { "Content-Type": "application/json; charset=utf-8" }, body };
          last = { statusCode: r.status, headers: { "Content-Type": "text/plain" }, body: body.slice(0, 200) };
        } catch (e) { last = { statusCode: 502, headers: { "Content-Type": "text/plain" }, body: String(e && e.message || e) }; }
      }
      return last;
    }
    return { statusCode: 400, headers: { "Content-Type": "text/plain" }, body: "unknown action" };
  } catch (e) {
    return { statusCode: 502, headers: { "Content-Type": "text/plain" }, body: "proxy error: " + (e && e.message ? e.message : String(e)) };
  }
};
