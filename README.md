# AutoBanSync

**AutoBanSync** ist ein hochleistungsfähiges, asynchrones Paper/Spigot-Plugin für Minecraft-Server (API-Version 1.21+), das deine lokale Server-Sperrliste automatisch mit einer zentralen REST-API / einem Webpanel synchronisiert. 

Es ermöglicht globale Moderation, indem es Sperren (Bans, Tempbans und Mutes) in Echtzeit vom Server an die API überträgt und regelmäßig die globale Liste abruft.

---

## Features

* **Vollständig Asynchron:** Keine Lags oder Aussetzer auf dem Server während des Netzwerkaustauschs.
* **Bi-direktionale Synchronisation:**
  * **Fetch:** Lädt externe Sperren automatisch in festgelegten Intervallen herunter und setzt sie lokal um.
  * **Push:** Ermöglicht das Bannen, temporäre Bannen und Muten direkt im Spiel mit automatischer Übertragung an die API.
* **Befehlsunterstützung:** `/autobansync ban`, `tempban`, `mute`, `sync`, `status` u.v.m.
* **Präzises Entbannen (Unban-Sync):** Wenn ein Eintrag aus der API entfernt wird, wird der betroffene Spieler (falls er vom Plugin gebannt wurde) automatisch entbannt.
* **Passby & Blacklist:**
  * **Passby-Liste:** Schützt bestimmte Spieler/UUIDs vor automatischen API-Sperren.
  * **Blacklist:** Kicked/Bannt gelistete Spieler direkt beim Betreten des Servers.
* **Standard Java HTTP-Client:** Nutzt moderne Java 11+ Features ohne schwere externe Abhängigkeiten (nur Google Gson & Adventure API).

---

## Installation

1. Lade die neueste `.jar`-Datei von AutoBanSync herunter.
2. Platziere die Datei im `plugins/`-Ordner deines Minecraft-Servers (Paper 1.21.x wird empfohlen).
3. Starte den Server einmalig neu, um die Konfigurationsdatei `config.yml` zu generieren.
4. Öffne `plugins/AutoBanSync/config.yml` und trage deine API-Endpunkte sowie deinen API-Token ein.
5. Führe `/autobansync reload` im Spiel oder in der Konsole aus.

---

## Konfiguration (`config.yml`)

```yaml
enabled: true

# API Endpunkte
api-url: "https://deine-domain.de/api/bans"
api-create-url: "https://deine-domain.de/api/create-ban"

# Authentifizierung
api-token: "DEIN_PERSOENLICHER_TOKEN_HIER"

# Synchronisations-Einstellungen
sync-interval-minutes: 10
sync-on-startup: true
connect-timeout-seconds: 10
read-timeout-seconds: 10
max-entries: 1000

# Nachrichten & Präfixe
ban-reason-prefix: "[AutoBanSync] "
kick-message: "Du wurdest auf Basis einer verifizierten Server-Meldung gebannt."
debug-mode: false

# Ausnahmen & Direkt-Blacklist
passby-players: []
blacklist-players: []
```

---

## Befehle & Rechte

| Befehl | Beschreibung | Rechte |
| :--- | :--- | :--- |
| `/autobansync status` | Zeigt den aktuellen Status & Zeit des letzten Syncs an | `autobansync.admin` |
| `/autobansync sync` | Führt sofort eine manuelle Synchronisation aus | `autobansync.admin` |
| `/autobansync reload` | Lädt die `config.yml` neu und startet den Timer neu | `autobansync.admin` |
| `/autobansync ban <Spieler> [Grund]` | Bannt einen Spieler permanent & sendet ihn an die API | `autobansync.admin` |
| `/autobansync tempban <Spieler> <Min> [Grund]` | Bannt einen Spieler temporär & sendet ihn an die API | `autobansync.admin` |
| `/autobansync mute <Spieler> [Grund]` | Synchronisiert einen Mute-Eintrag an die API | `autobansync.admin` |
| `/autobansync token` | Zeigt Informationen zur aktuellen API-Konfiguration | `autobansync.admin` |

> **Berechtigung:** `autobansync.admin` (Standardmäßig für Operatoren / OP)

---

## API Schnittstelle (JSON-Format)

### 1. Bans Abrufen (`GET` auf `api-url`)
Erwartetes JSON-Format der API:
```json
{
  "version": 1,
  "generated_at": "2026-10-15T12:00:00Z",
  "entries": [
    {
      "uuid": "069a79f4-44e9-4726-a5be-fef90e38aaf5",
      "username": "Gamer123",
      "reason": "Hacking / Cheating",
      "timestamp": 1700000000,
      "entry_id": "101",
      "ban_type": "ban",
      "duration_seconds": 0,
      "server_name": "Lobby-1"
    }
  ]
}
```

### 2. Ban Erstellen (`POST` auf `api-create-url`)
Das Plugin sendet folgende JSON-Struktur mit einem optionalen `Authorization: Bearer <token>` Header:
```json
{
  "username": "Gamer123",
  "uuid": "069a79f4-44e9-4726-a5be-fef90e38aaf5",
  "ban_type": "tempban",
  "duration_seconds": 3600,
  "reason": "Griefing",
  "server_name": "In-Game"
}
```

---

## Kompilieren & Bauen

Das Projekt kann einfach mit **Gradle** gebaut werden:

```bash
git clone https://github.com/Airushmeos/AutoBanSync.git
cd AutoBanSync
./gradlew build
```
Die fertige `.jar`-Datei befindet sich anschließend unter `build/libs/`.
