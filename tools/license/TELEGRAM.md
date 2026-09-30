# Активация с телефона через Telegram-бот (работает в России)

Вы пишете боту 6-значный код магнитолы — он отвечает кодом активации. Всё через приложение Telegram,
которое в России не блокируется. Секрет остаётся на сервере, в чат не попадает.

## Настройка (один раз, на Mac)

1. В Telegram напишите **@BotFather** → `/newbot` → придумайте имя. Получите **токен** вида `12345:AbC...`.
2. В Telegram напишите **@userinfobot** — он пришлёт ваш **id** (число).
3. В папке `tools/license` задайте секреты воркера и опубликуйте (secret'ы вводятся по запросу):

```
cd ~/Проекты/MinimalLauncher/tools/license
npx wrangler secret put SECRET_HEX
npx wrangler secret put TG_TOKEN
npx wrangler secret put TG_OWNER
npx wrangler deploy
```
- `SECRET_HEX` — содержимое `~/Проекты/MinimalLauncher-secret/hmac_secret.hex` (ВАЖНО: секрет недавно сменился, обновите его в воркере).
- `TG_TOKEN` — токен бота из шага 1.
- `TG_OWNER` — ваш id из шага 2.

4. Привяжите бота к воркеру (подставьте токен дважды):

```
curl "https://api.telegram.org/bot<ТОКЕН>/setWebhook?url=https://minimal-drive-license.byefimovnikita.workers.dev/tg/<ТОКЕН>"
```
Должно ответить `{"ok":true,...}`.

## Использование (с телефона)

Откройте своего бота в Telegram, отправьте 6-значный код с экрана магнитолы — бот пришлёт код активации.
Введите его на магнитоле в поле «Код активации». Бот отвечает только вам (по вашему id).
