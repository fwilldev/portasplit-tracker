# PortaSplit Tracker
![img.png](img.png)
Überwacht die Verfügbarkeit der **Midea PortaSplit** (und PortaSplit Cool) bei mehreren
Baumärkten sowie Amazon, Lidl und kleinanzeigen.de - und schickt eine **Telegram**-Nachricht,
sobald ein Gerät verfügbar wird. Läuft als kleine Spring-Boot-App mit eingebautem Dashboard.

> Inoffizielles, nicht-kommerzielles Hobby-Projekt. Keine Zugehörigkeit zu oder Kooperation mit den
> genannten Händlern/Marken - alle Marken- und Produktnamen gehören ihren jeweiligen Inhabern.
>
> 🚧 **Work-in-Progress**

<a href='https://ko-fi.com/J3G820LBTF' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>


## Was es kann

- Prüft **OBI, toom, Bauhaus, Hornbach, Globus** (Filialen + Online-Shop) sowie **Amazon**, **Lidl**
  und **kleinanzeigen.de** - jede Quelle läuft als eigener, unabhängiger Worker
- **Telegram-Alarm**, sobald ein Produkt von „nicht verfügbar" auf „verfügbar" wechselt, bzw. bei
  einem neuen kleinanzeigen-Angebot
- **Dashboard** mit Live-Status, Verlaufsdiagramm, Änderungs-Feed und technischem Logbuch
- **Umkreissuche**: Filialen per PLZ + Radius ein-/ausblenden (Online-Shops zählen immer)
- Historie & Logging zur Nachvollziehbarkeit
- Mit **[CloakBrowser](https://github.com/CloakHQ/cloakbrowser)** werden Bot-Protections weitesgehend umgangen!

## Installation

> 🤖 **Mit einem KI-Agenten installieren?** Gib deinem Coding-Agenten (Claude Code, Claude Cowork,
> opencode, …) die Datei **[AGENT_SETUP.md](AGENT_SETUP.md)** — sie führt ihn Schritt für Schritt
> durch eine geführte Installation und Konfiguration auf deinem PC.

**Voraussetzungen:** JDK 21+, Maven 3.9+ (für den lokalen Start) bzw. Docker (empfohlen).

### Docker (empfohlen)

```bash
cp .env.example .env      # Telegram-Werte eintragen, siehe unten
docker compose up -d --build
```

Startet die App **und** den CloakBrowser. Dashboard danach
unter **http://localhost:8080** verfügbar.

#### Ohne lokalen Build (vorgebautes Image)

Bei jedem Push auf `main` baut GitHub Actions automatisch ein Image für `linux/amd64` **und**
`linux/arm64` und veröffentlicht es nach `ghcr.io/fwilldev/portasplit-tracker` (Tags: `latest`,
Branch-/Versions-Tags und ein Datums-Tag). So sparst du dir den lokalen Maven-/Node-Build. Ersetze
dazu in `docker-compose.yml` beim `app`-Service die Zeile `build: .` durch:

```yaml
    image: ghcr.io/fwilldev/portasplit-tracker:latest
```

und starte dann ohne `--build`:

```bash
docker compose pull
docker compose up -d
```

CloakBrowser bleibt ein eigenständiger Container und wird von Compose weiterhin mitgestartet — nur
der Build der App entfällt.

### Lokal mit Maven

```bash
cp local.properties.example local.properties   # Telegram-Werte eintragen
mvn spring-boot:run
```

Dashboard unter **http://localhost:8080**, SQLite-Datei landet automatisch unter `./data/portasplit.db`.
Zusätzlich wird Cloakbrowser benötigt:
`docker compose up -d cloakbrowser`.

## Konfiguration

### Kleinanzeigen

Um Kleinanzeigen zu prüfen, muss ein gültiger Suchlink in Kleinanzeigen erstellt werden.

Dazu am besten "portasplit" o.Ä. in Kleinanzeigen suchen, Umkreis einstellen und den Link in den Eintellungen speichern. Der Bot wird automatisch aktiv und listet entsprechende Inserate im Dashboard.

### Telegram

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

### Automatisch Reservieren Bot (ALPHA)

Damit der Bot automatisch versuchen kann, ein Gerät zu reservieren, ist es von Vorteil (oder wird gar benötigt) mit einem Account eingeloggt zu sein.

Für einige dieser Bots benötigt es einen AES-Key zur Verschlüsselung des Passwortes.

AES-Key generieren mit `openssl rand -base64 32` (Unix-Systeme).

Und in der `.env` oder `local.properties`:

```dotenv
RESERVE_CRYPTO_KEY=
```

eintragen.

Dann im Dashboard unter Einstellungen die Credentials eintragen für die jeweiligen Shops.

Der Bot versucht dann bei Verfügbarkeit ein Gerät zu reservieren, in dem im eingeloggten Zustand das Gerät in den Warenkorb gelegt wird und zum Checkout geht.

War dies erfolgreich, erfolgt eine Telegram Benachrichtigung. Der Prozess muss dann vom User über Telegram bestätigt werden.

## Disclaimer

Dieses Projekt ist ein **nicht-kommerzielles Hobby- und Proof-of-Concept-Projekt** und
befindet sich in aktiver Entwicklung (Work-in-Progress). Es wird ohne Gewähr und
ausschließlich zu privaten, experimentellen und Lernzwecken bereitgestellt. Es besteht
keine Verbindung zu den genannten Händlern oder Marken.

**Verantwortung des Nutzers.** Wer dieses Tool einsetzt, ist **allein selbst dafür
verantwortlich**, die Nutzungsbedingungen (AGB), die `robots.txt` sowie das geltende
Recht der jeweils abgefragten Seiten einzuhalten.

**Kein Missbrauch.** Das Projekt darf nicht dazu verwendet werden, Systeme zu stören,
Schutzmaßnahmen unzulässig zu umgehen, Waren zu horten/weiterzuverkaufen (Scalping)
oder in sonstiger Weise rechtswidrig oder missbräuchlich zu handeln.

Alle Marken- und Produktnamen gehören ihren jeweiligen Inhabern.

