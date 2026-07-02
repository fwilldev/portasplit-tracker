# PortaSplit Tracker
![img.png](img.png)
Überwacht die Verfügbarkeit der **Midea PortaSplit** (und PortaSplit Cool) bei mehreren
Bau-/Klimamärkten sowie Amazon, Lidl und kleinanzeigen.de - und schickt eine **Telegram**-Nachricht,
sobald ein Gerät verfügbar wird. Läuft als kleine Spring-Boot-App mit eingebautem Dashboard.

> Inoffizielles, nicht-kommerzielles Hobby-Projekt. Keine Zugehörigkeit zu oder Kooperation mit den
> genannten Händlern/Marken - alle Marken- und Produktnamen gehören ihren jeweiligen Inhabern.

<a href='https://ko-fi.com/J3G820LBTF' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>


## Was es kann

- Prüft **OBI, toom, Bauhaus, Hornbach, Globus** (Filialen + Online-Shop) sowie **Amazon**, **Lidl**
  und **kleinanzeigen.de** - jede Quelle läuft als eigener, unabhängiger Worker
- **Telegram-Alarm**, sobald ein Produkt von „nicht verfügbar" auf „verfügbar" wechselt, bzw. bei
  einem neuen kleinanzeigen-Angebot
- **Dashboard** mit Live-Status, Verlaufsdiagramm, Änderungs-Feed und technischem Logbuch
- **Umkreissuche**: Filialen per PLZ + Radius ein-/ausblenden (Online-Shops zählen immer)
- Quellen einzeln zur Laufzeit an-/abschaltbar, keine Neustarts nötig
- **SQLite** lokal im Projekt - keine externe Datenbank nötig
- Amazon/Lidl/Bauhaus/kleinanzeigen laufen über einen **CloakBrowser** (Stealth-Chromium via Docker),
  da diese Seiten Bot-Schutz oder eine reine SPA ohne Bestands-API haben

## Installation

**Voraussetzungen:** JDK 21+, Maven 3.9+ (für den lokalen Start) bzw. Docker (empfohlen).

### Docker (empfohlen)

```bash
cp .env.example .env      # Telegram-Werte eintragen, siehe unten
docker compose up -d --build
```

Startet die App **und** den CloakBrowser. Dashboard danach
unter **http://localhost:8080** verfügbar.

### Lokal mit Maven

```bash
cp local.properties.example local.properties   # Telegram-Werte eintragen
mvn spring-boot:run
```

Dashboard unter **http://localhost:8080**, SQLite-Datei landet automatisch unter `./data/portasplit.db`.
Zusätzlich wird Cloakbrowser benötigt:
`docker compose up -d cloakbrowser`.

## Telegram einrichten

1. Bei [@BotFather](https://t.me/BotFather) einen Bot anlegen → **Bot-Token** kopieren.
2. Dem Bot eine Nachricht schreiben, dann die eigene **Chat-ID** abrufen, z. B. über
   `https://api.telegram.org/bot<TOKEN>/getUpdates`.
3. Token und Chat-ID in `.env` bzw. `local.properties` eintragen:

   ```properties
   TELEGRAM_ENABLED=true
   TELEGRAM_BOT_TOKEN=123456789:AA-dein-token
   TELEGRAM_CHAT_ID=987654321
   ```

4. App starten - im Dashboard testet der Button **„Telegram testen"** die Konfiguration.

Weitere Einstellungen (Shops, Radius, Amazon/Lidl/kleinanzeigen-URLs, Ports, …) stehen in
`local.properties.example` bzw. `src/main/resources/application.yml`.
