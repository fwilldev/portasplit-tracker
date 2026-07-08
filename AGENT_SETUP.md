# PortaSplit Tracker — Geführte Installation für KI-Agenten

> **Diese Datei ist für einen KI-Coding-Agenten (Claude Code, Claude Cowork, opencode, Cursor,
> Aider o. Ä.) geschrieben, nicht primär für Menschen.** Wenn du ein solcher Agent bist:
> Arbeite die Phasen **der Reihe nach** ab, führe die angegebenen Befehle selbst aus, prüfe nach
> jeder Phase das Ergebnis und **frage den Nutzer** an den mit 🧑 markierten Stellen. Fahre erst
> fort, wenn die ✅-Prüfung der aktuellen Phase erfolgreich war.

## Konventionen in diesem Dokument

| Symbol | Bedeutung |
| --- | --- |
| 🧑 **Frage** | Eingabe/Entscheidung des Nutzers nötig — anhalten und fragen. |
| ▶️ **Aktion** | Befehl, den *du als Agent* ausführst. |
| ✅ **Prüfen** | Erfolgskriterium; erst weiter, wenn erfüllt. |
| ⚠️ | Häufige Fehlerquelle / Sicherheitshinweis. |

## Was am Ende läuft

Eine kleine Spring-Boot-App mit React-Dashboard, die die Verfügbarkeit der **Midea PortaSplit**
(und *PortaSplit Cool*) bei mehreren Baumärkten, Amazon, Lidl und kleinanzeigen.de überwacht und
bei Verfügbarkeit eine **Telegram**-Nachricht schickt. Der Stack besteht aus zwei Teilen:

1. **cloakbrowser** — ein Stealth-Chromium als CDP-Server (Docker-Image `cloakhq/cloakbrowser`),
   das Bot-Schutz (u. a. Cloudflare Turnstile) umgeht. Nötig für Amazon, Lidl, kleinanzeigen und
   die Cloudflare-geschützten Ketten.
2. **app** — der Tracker selbst. Dashboard nach dem Start unter **http://localhost:8080**.

Datenbank ist eine SQLite-Datei unter `./data/portasplit.db` (wird automatisch angelegt).

---

## Phase 0 — Umgebung prüfen & Installationsweg wählen

Es gibt zwei Wege. **Docker ist empfohlen** (baut Frontend + Backend + CloakBrowser in einem
Rutsch, keine lokale JDK/Node-Installation nötig).

▶️ **Aktion:** Verfügbare Tools ermitteln (Fehler einzelner Befehle sind ok — sie zeigen nur, was
fehlt):

```bash
docker --version && docker compose version   # Weg A (Docker)
java -version                                 # Weg B braucht JDK 21+
mvn -version                                  # Weg B braucht Maven 3.9+
git --version
```

🧑 **Frage (nur wenn beide Wege möglich sind):** „Soll ich den empfohlenen **Docker**-Weg nehmen
oder **lokal mit Maven** starten?"

Entscheidungshilfe:

- **Weg A — Docker** (empfohlen): braucht nur Docker + Docker Compose. CloakBrowser läuft
  automatisch mit. → weiter bei **Phase 1**, dann **Phase 2A**.
- **Weg B — Maven**: braucht **JDK 21+** und **Maven 3.9+** lokal. Node wird beim Build vom
  `frontend-maven-plugin` automatisch heruntergeladen (Node v22.12.0), muss also *nicht* installiert
  sein. CloakBrowser muss trotzdem als Docker-Container laufen. → weiter bei **Phase 1**, dann
  **Phase 2B**.

⚠️ Wenn Docker fehlt **und** (kein JDK 21+ **oder** kein Maven), halte an und nenne dem Nutzer
konkret, was zu installieren ist (Docker Desktop **oder** Temurin JDK 21 + Maven 3.9). Rate nicht.

---

## Phase 1 — Quellcode besorgen

▶️ **Aktion:** Prüfe, ob du bereits im Projektverzeichnis bist:

```bash
test -f pom.xml && test -f docker-compose.yml && echo "REPO_OK" || echo "REPO_MISSING"
```

- `REPO_OK` → du bist im Projekt, weiter zu Phase 2.
- `REPO_MISSING` → 🧑 **Frage** nach der Repo-URL bzw. dem Zielordner und klone dann dorthin
  (`git clone <url>` und in den Ordner wechseln). Danach die Prüfung oben wiederholen.

✅ **Prüfen:** `pom.xml`, `docker-compose.yml`, `.env.example` und `local.properties.example`
liegen im aktuellen Verzeichnis.

---

## Phase 2 — Geheimnisse einsammeln (Telegram & Optionales)

Diese Werte kommen **vom Nutzer** und dürfen **niemals** committet werden (`.env` und
`local.properties` sind in `.gitignore`).

### 2.1 Telegram (empfohlen, sonst keine Alarme)

🧑 **Führe den Nutzer durch die Telegram-Einrichtung** (jeder Schritt einzeln abfragen):

1. In Telegram [@BotFather](https://t.me/BotFather) öffnen → `/newbot` → Namen/Username vergeben →
   den **Bot-Token** kopieren (Format `123456789:AA…`).
2. Dem neu erstellten Bot **eine beliebige Nachricht** schicken (sonst liefert `getUpdates` keine
   Chat-ID).
3. Chat-ID abrufen. Sobald du den Token hast, kannst *du* das für den Nutzer erledigen:

   ```bash
   curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates"
   ```

   In der JSON-Antwort steht die numerische **Chat-ID** unter `result[].message.chat.id`.

✅ **Prüfen:** Du hast **Bot-Token** und **Chat-ID**. Falls `getUpdates` ein leeres `result`
liefert → der Nutzer hat dem Bot noch nicht geschrieben; Schritt 2 wiederholen.

### 2.2 kleinanzeigen.de (optional)

🧑 **Frage:** „Soll kleinanzeigen.de überwacht werden?" Wenn ja: Der Nutzer sucht auf
kleinanzeigen.de nach dem Produkt (z. B. „portasplit"), stellt den Umkreis ein und kopiert die
**Ergebnis-URL**. Diese URL ist gleichzeitig der An/Aus-Schalter. Beispiel:
`https://www.kleinanzeigen.de/s-anzeige:angebote/midea-portasplit/k0c98`
(kann auch später im Dashboard gesetzt werden → dann hier überspringen).

### 2.3 Auto-Reservieren-Schlüssel (optional, ALPHA)

🧑 **Frage:** „Soll die **automatische Reservierung** (ALPHA) vorbereitet werden?" Wenn ja, wird ein
AES-Schlüssel benötigt, mit dem gespeicherte Shop-Passwörter verschlüsselt werden.

▶️ **Aktion (nur auf Unix/macOS):**

```bash
openssl rand -base64 32
```

Den ausgegebenen Wert als `RESERVE_CRYPTO_KEY` verwenden. Ohne Schlüssel bleibt das Feature
deaktiviert (es wird nichts im Klartext gespeichert). Die eigentlichen Shop-Zugangsdaten trägt der
Nutzer **später im Dashboard** ein (Phase 5), nicht hier.

---

## Phase 2A — Konfiguration schreiben (Docker-Weg)

▶️ **Aktion:** `.env` aus der Vorlage erzeugen (falls noch nicht vorhanden) und die Werte aus
Phase 2 eintragen. Vorlage:

```dotenv
TELEGRAM_BOT_TOKEN=123456789:AA-your-bot-token
TELEGRAM_CHAT_ID=987654321
RESERVE_CRYPTO_KEY=
```

Vorgehen:

1. `cp .env.example .env` (nur wenn `.env` noch nicht existiert — vorhandene `.env` **nicht**
   überschreiben, sondern gezielt editieren).
2. `TELEGRAM_BOT_TOKEN` und `TELEGRAM_CHAT_ID` mit den echten Werten füllen.
3. Optional `RESERVE_CRYPTO_KEY` setzen (aus 2.3).
4. Optional `KLEINANZEIGEN_URL=<url>` als zusätzliche Zeile ergänzen (aus 2.2), wenn jetzt schon
   gewünscht.

ℹ️ Hinweis: `docker-compose.yml` setzt `TELEGRAM_ENABLED`, `AMAZON_ENABLED` und `LIDL_ENABLED` fest
auf `true` — im Docker-Weg sind Telegram, Amazon und Lidl also standardmäßig aktiv, sobald die
Secrets in `.env` stehen.

✅ **Prüfen:** `.env` enthält Token und Chat-ID; keine Beispiel-Platzhalter (`AA-your-bot-token`)
mehr. → weiter zu **Phase 3A**.

---

## Phase 2B — Konfiguration schreiben (Maven-Weg)

▶️ **Aktion:** `local.properties` aus der Vorlage erzeugen und füllen. Diese Datei wird beim Start
automatisch über `spring.config.import` geladen.

1. `cp local.properties.example local.properties` (vorhandene Datei nicht überschreiben).
2. Mindestens setzen:

   ```properties
   TELEGRAM_ENABLED=true
   TELEGRAM_BOT_TOKEN=123456789:AA-dein-token
   TELEGRAM_CHAT_ID=987654321
   ```

3. Optional in derselben Datei aktivieren (jeweils Kommentar entfernen):

   ```properties
   CLOAKBROWSER_CDP_URL=http://localhost:9222
   AMAZON_ENABLED=true
   LIDL_ENABLED=true
   KLEINANZEIGEN_URL=https://www.kleinanzeigen.de/s-anzeige:angebote/midea-portasplit/k0c98
   RESERVE_CRYPTO_KEY=<aus openssl rand -base64 32>
   ```

⚠️ Amazon, Lidl und kleinanzeigen brauchen den laufenden CloakBrowser (Phase 3B). Ohne ihn bleiben
diese Checks ohne Ergebnis.

✅ **Prüfen:** `local.properties` enthält gültige Telegram-Werte. → weiter zu **Phase 3B**.

---

## Phase 3A — Starten (Docker-Weg)

▶️ **Aktion:**

```bash
docker compose up -d --build
```

Das baut Frontend + Backend, startet **cloakbrowser** und **app**. Der erste Build dauert länger
(Maven-Deps + Node-Download im Image).

💡 **Alternative ohne lokalen Build:** Es gibt ein von GitHub Actions vorgebautes Image für
`linux/amd64` **und** `linux/arm64` unter `ghcr.io/fwilldev/portasplit-tracker` (Tag `latest`). Wenn
kein lokaler Build gewünscht ist (z. B. schwacher Server, kein JDK/Node nötig), ersetze in
`docker-compose.yml` beim `app`-Service `build: .` durch
`image: ghcr.io/fwilldev/portasplit-tracker:latest` und starte stattdessen:

```bash
docker compose pull
docker compose up -d
```

CloakBrowser bleibt in beiden Fällen ein separater Container und wird mitgestartet.

✅ **Prüfen:**

```bash
docker compose ps                                   # beide Services "running"/"healthy"
curl -fs http://localhost:8080/actuator/health      # erwartet {"status":"UP"}
```

Wenn `health` noch nicht `UP` ist: bis zu ~60 s warten (Startup-Grace) und erneut prüfen. → weiter
zu **Phase 4**.

---

## Phase 3B — Starten (Maven-Weg)

▶️ **Aktion:** Zuerst den CloakBrowser als Container starten, dann die App lokal:

```bash
docker compose up -d cloakbrowser
mvn spring-boot:run
```

`mvn spring-boot:run` blockiert das Terminal (Vordergrundprozess). Führe es daher als
Hintergrund-/getrennten Prozess aus, damit du die folgenden Prüfungen machen kannst, und
beobachte die Logs auf `Started …Application`.

✅ **Prüfen:**

```bash
curl -fs http://localhost:8080/actuator/health      # erwartet {"status":"UP"}
```

Die SQLite-Datei erscheint automatisch unter `./data/portasplit.db`. → weiter zu **Phase 4**.

---

## Phase 4 — Funktion verifizieren

▶️ **Aktion:** Dashboard öffnen bzw. abrufen:

```bash
curl -fsI http://localhost:8080/    # HTTP 200
```

🧑 **Bitte den Nutzer**, **http://localhost:8080** im Browser zu öffnen. Sichtbar sein sollten:
Live-Status der Quellen, Verlaufsdiagramm, Änderungs-Feed und ein technisches Logbuch.

🧑 **Telegram testen:** Im Dashboard unter **Einstellungen** den Button **„Telegram testen"**
drücken. Es sollte eine Testnachricht im Telegram-Chat ankommen.

✅ **Prüfen:** Dashboard lädt **und** die Telegram-Testnachricht kam an. Wenn nicht →
**Troubleshooting**.

---

## Phase 5 — Laufzeit-Konfiguration im Dashboard (optional)

Diese Dinge werden **nicht** über Dateien, sondern zur Laufzeit im Dashboard eingestellt:

- **Umkreissuche:** PLZ + Radius setzen, um Filialen ein-/auszublenden (Online-Shops zählen immer).
- **kleinanzeigen-URL:** falls in Phase 2.2 nicht per Datei gesetzt, hier eintragen.
- **Auto-Reservieren (ALPHA):** nur nutzbar, wenn `RESERVE_CRYPTO_KEY` gesetzt war. Der Nutzer
  trägt pro Shop seine **Login-Zugangsdaten** ein; das Passwort wird mit dem AES-Key verschlüsselt
  gespeichert. Bei Verfügbarkeit legt der Bot das Gerät im eingeloggten Zustand in den Warenkorb und
  geht zum Checkout; erfolgreiche Reservierungen müssen per Telegram bestätigt werden.

🧑 Frage den Nutzer, welche dieser Optionen er einrichten möchte, und leite ihn durch die
Dashboard-Einstellungen.

---

## Konfigurations-Referenz (Umgebungsvariablen)

Gelten für `.env` (Docker) bzw. `local.properties` (Maven). Vollständige Liste inkl. Poll-Intervalle
in `src/main/resources/application.yml`.

| Variable | Default | Zweck |
| --- | --- | --- |
| `TELEGRAM_ENABLED` | `false` | Telegram-Benachrichtigungen an/aus (Docker setzt `true`). |
| `TELEGRAM_BOT_TOKEN` | – | BotFather-Token. |
| `TELEGRAM_CHAT_ID` | – | Ziel-Chat-ID. |
| `AMAZON_ENABLED` | `false` | Amazon-Check (braucht CloakBrowser; Docker setzt `true`). |
| `AMAZON_MAX_DELIVERY_DAYS` | `5` | Nur „verfügbar", wenn Versand innerhalb N Tagen. |
| `AMAZON_REQUIRE_PRIME` | `false` | `true` = nur mit Prime/schnellem Versand „verfügbar". |
| `LIDL_ENABLED` | `false` | Lidl-Check, nur PortaSplit **Cool** (Docker setzt `true`). |
| `KLEINANZEIGEN_URL` | – | Such-URL = An/Aus-Schalter für den kleinanzeigen-Watcher. |
| `KLEINANZEIGEN_FRESHNESS_MINUTES` | `3` | Nur Inserate der letzten N Minuten melden. |
| `RESERVE_CRYPTO_KEY` | – | AES-Key für Auto-Reservieren (`openssl rand -base64 32`). |
| `CLOAKBROWSER_CDP_URL` | `http://localhost:9222` | CDP-Endpoint des CloakBrowsers. |
| `SERVER_PORT` | `8080` | Port des Dashboards. |
| `PORTASPLIT_DB_PATH` | `./data/portasplit.db` | Pfad der SQLite-Datei. |

**Ketten-Checker** (in `application.yml`): OBI, toom, Globus, **Hornbach** und **Bauhaus** sind
standardmäßig aktiv; **Hagebau** ist aus. Änderungen dort nur bei Bedarf.

---

## Troubleshooting

| Symptom | Ursache & Lösung |
| --- | --- |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | Port 8080 belegt. Anderen Dienst stoppen **oder** `SERVER_PORT` ändern. Im Docker-Weg wird 8080 vom **cloakbrowser**-Service veröffentlicht (geteilter Netzwerk-Namespace) — dort das Port-Mapping anpassen, nicht am `app`-Service. |
| `Handshake error` / CDP `403` beim Verbinden zum CloakBrowser | Der CDP-Server akzeptiert WebSocket-Upgrades nur über **loopback** mit Origin-Header. Im Docker-Weg teilt sich `app` bewusst den Netzwerk-Namespace des CloakBrowsers (`network_mode: service:cloakbrowser`) und erreicht ihn über `http://localhost:9222` — dieses Setup nicht verändern. Im Maven-Weg muss `CLOAKBROWSER_CDP_URL=http://localhost:9222` zeigen und der Container laufen. |
| Cloudflare/Turnstile blockt weiterhin | CloakBrowser läuft absichtlich **headed** (`cloakserve --headless=false` via Xvfb) — headless wird oft erkannt. Diese Zeile in `docker-compose.yml` nicht auf headless umstellen. Blocks können intermittierend auftreten; das Failover fängt das meist ab. |
| `database is locked` | SQLite erlaubt nur einen Writer; der Pool ist deshalb auf 1 begrenzt. Nicht zwei App-Instanzen gleichzeitig auf dieselbe `.db` laufen lassen. |
| Keine Telegram-Nachricht | 1) `TELEGRAM_ENABLED=true`? 2) Token/Chat-ID korrekt und ohne Platzhalter? 3) Hat der Nutzer dem Bot mindestens einmal geschrieben? 4) `getUpdates` erneut prüfen. |
| `getUpdates` liefert leeres `result` | Der Nutzer muss dem Bot **zuerst** eine Nachricht senden, dann erneut abrufen. |
| Erster Docker-Build sehr langsam / scheint zu hängen | Normal: Maven lädt Dependencies, das Frontend-Plugin lädt Node herunter. Bei `docker compose up --build` (ohne `-d`) die Log-Ausgabe beobachten. |
| Health bleibt lange „starting" | `start_period` beträgt 60 s. Erst danach als Fehler werten. |

⚠️ **Rabbit-Hole-Stop:** Wenn ein Schritt nach **2–3 Versuchen** weiter fehlschlägt, halte an,
fasse zusammen, was du versucht hast und welche Fehlermeldung kam, und frage den Nutzer, wie er
fortfahren möchte. Nicht denselben fehlschlagenden Befehl wiederholt ausführen.

---

## Leitplanken für den Agenten (wichtig)

- **Keine Geheimnisse committen.** `.env` und `local.properties` bleiben lokal (stehen in
  `.gitignore`). Token/Chat-ID/AES-Key niemals in Commits, Logs oder Ausgaben offenlegen.
- **Bestehende Config nicht blind überschreiben.** Vor `cp …` prüfen, ob die Zieldatei existiert;
  sonst gezielt editieren.
- **Poll-Intervalle nicht aggressiv senken.** Die Defaults (45–75 s pro Quelle) sind bewusst
  gewählt, um Händler-Server nicht zu belasten. Nicht ohne ausdrücklichen Nutzerwunsch verringern.
- **Rechtlicher Rahmen.** Dies ist ein nicht-kommerzielles Hobby-/Proof-of-Concept-Projekt. Der
  Nutzer ist selbst dafür verantwortlich, AGB, `robots.txt` und geltendes Recht der abgefragten
  Seiten einzuhalten. Kein Einsatz zum Horten/Scalping oder zur unzulässigen Umgehung von
  Schutzmaßnahmen. Siehe Disclaimer in der `README.md`.
