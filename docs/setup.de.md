# VDTelemetry — Einrichtung

*[This guide in English](setup.en.md)*

VDTelemetry zeigt live an, was deine Maschine und dein Hof im Landwirtschafts-Simulator 25 gerade
machen — auf einem zweiten Bildschirm, im Browser auf demselben PC oder auf einem Tablet oder Handy
in der Kabine. Es besteht aus zwei Teilen, und du brauchst beide:

- eine **Mod**, die den Spielzustand auf die Festplatte schreibt, und
- **VDTerminal**, ein kleines Programm, das diese Dateien liest und das Dashboard bereitstellt.

![Das Dashboard](https://raw.githubusercontent.com/VertexDezign/VDTelemetry/main/screenshots/overview.png)

## Was du brauchst

| | |
|---|---|
| **Das Spiel** | Landwirtschafts-Simulator 25, unter Windows oder unter Linux mit Proton |
| **Eine zweite Mod** | [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs/releases), Version **1.2 oder neuer** (jede 1.x). Ohne sie kann VDTelemetry nicht laufen und schaltet sich selbst ab |
| **Einen Browser** | Ein aktueller Browser wie Chrome, Edge oder Firefox — auf dem Spiele-PC, und auf dem Tablet oder Handy, falls du eines nutzen möchtest |

Alles läuft auf deinem eigenen Rechner. Es wird nichts hochgeladen.

## 1. Die beiden Mods installieren

Lade beide Zip-Dateien herunter:

- `FS25_vdTelemetry.zip` — aus den [Releases dieses Projekts](https://github.com/VertexDezign/VDTelemetry/releases)
- `FS25_additionalInputs.zip` — von [der eigenen Releases-Seite](https://github.com/VertexDezign/AdditionalInputs/releases)

Kopiere **beide Zip-Dateien, ungeöffnet**, in deinen FS25-`mods`-Ordner:

- **Windows:** `Documents\My Games\FarmingSimulator2025\mods` — der Ordner heißt auf der Platte
  `Documents`, auch wenn der Explorer ihn als **Dokumente** anzeigt.
  Wenn dein Dokumente-Ordner mit OneDrive synchronisiert wird, liegt er stattdessen unter
  `OneDrive\Documents\...` — das ist normal, VDTerminal schaut an beiden Stellen nach.
- **Linux (Steam/Proton):** tief im Proton-Prefix. So findest du ihn:
  ```bash
  find ~ -type d -path '*FarmingSimulator2025/mods' 2>/dev/null
  ```

Starte das Spiel, lade oder erstelle einen Spielstand und **hake beide Mods** in der Mod-Auswahl an.

## 2. VDTerminal starten

Lade das Archiv für dein System von derselben Releases-Seite herunter und entpacke es, wohin du
möchtest — der Desktop reicht völlig. Es gibt kein Installationsprogramm, und es wird nichts an
deinem System verändert.

- **Windows** — starte `VDTerminal\VDTerminal.exe`.
  Windows warnt, dass das Programm nicht signiert ist („Der Computer wurde durch Windows
  geschützt“). Wähle **Weitere Informationen → Trotzdem ausführen**. Es öffnet sich ein schwarzes
  Konsolenfenster und bleibt offen: dieses Fenster *ist* das Programm — lass es laufen und schließe
  es erst, wenn du mit dem Spielen fertig bist.
  Beim ersten Start fragt die Windows-Firewall nach Netzwerkzugriff. **Erlaube ihn für private
  Netzwerke** — ohne das erreicht das Tablet das Dashboard nicht.
- **Linux** — entpacke das `.tar.gz` und starte `VDTerminal/bin/VDTerminal` in einem Terminal.

Du musst **kein** Java installieren. Das Paket bringt sein eigenes mit.

Die Konsole gibt die Adressen aus, die du im nächsten Schritt brauchst:

```text
Game directory: /home/du/.steam/.../FarmingSimulator2025
Server starting on port 3001
Dashboard: http://localhost:3001
  from another device: http://192.168.1.42:3001
```

## 3. Das Dashboard öffnen

- **Auf dem Spiele-PC:** <http://localhost:3001>
- **Auf Tablet oder Handy:** die Adresse hinter `from another device`, also z. B.
  `http://192.168.1.42:3001`. Das Gerät muss im selben Netzwerk (WLAN) sein wie der PC.

Auf dem Tablet lohnt sich **Zum Startbildschirm hinzufügen**: das gibt ein eigenes Symbol und ein
Vollbild ohne Browserleisten.

**Damit der Bildschirm anbleibt.** Tipp einmal auf das Kaffeetassen-Symbol in der Kopfzeile: Es zeigt
**AWAKE**, solange das Dashboard den Bildschirm wachhält; ein weiterer Tipp lässt ihn wieder schlafen.
Dieser eine Tipp ist wichtig — auf iPad und iPhone kann das Dashboard den Bildschirm erst wachhalten,
nachdem du es einmal berührt hast; ein Tablet, das du nur anschaust, wird also dunkel. Im
Anzeigemodus (siehe unten) gibt es keine Kopfzeile, dort genügt eine Berührung irgendwo.

Drei Dinge, die du wissen solltest:

- Auf Handy und Tablet läuft dafür ein **stummer** Videoclip, den das Gerät als Wiedergabe zählt —
  das kann Musik oder einen Podcast unterbrechen, der gerade lief. Wenn dich das stört, lass die
  Kaffeetasse aus und nimm stattdessen die Einstellung unten.
- Auf dem iPad ist dieser Weg bestätigt, unter **Android** ist er noch auf keinem Gerät geprüft.
  Zeigt die Kopfzeile **AWAKE** und der Bildschirm wird trotzdem dunkel, nimm die Einstellung unten.
- Es gilt nur, solange du das Dashboard auch ansiehst. Wechselst du die App oder den Tab, gilt wieder
  der normale Bildschirm-Timeout des Geräts.

Die Einstellung, die immer funktioniert und sich für ein Tablet in der Kabine lohnt: *Einstellungen →
Anzeige & Helligkeit → Automatische Sperre → Nie* unter iOS, *Einstellungen → Display →
Bildschirm-Timeout* unter Android. Am Ladekabel hängt es dort oben ohnehin meistens.

## 4. Prüfen, ob Daten ankommen

Lade einen Spielstand und setz dich in ein Fahrzeug. Das Dashboard sollte sich innerhalb von ein bis
zwei Sekunden füllen.

Wenn das klappt, bist du fertig — der Rest dieser Seite wird nur gebraucht, wenn etwas fehlt.

## Wenn das Dashboard leer bleibt

Arbeite die Liste von oben nach unten ab; sie ist danach sortiert, wie häufig der jeweilige Punkt
die Ursache ist.

**Die Seite meldet, dass sie nicht verbinden kann, oder lädt gar nicht**

- VDTerminal läuft nicht, oder das Konsolenfenster wurde geschlossen. Starte es erneut.
- Auf dem Tablet: falsche Adresse, falsches Netzwerk, oder die Firewall-Abfrage wurde weggeklickt.
  Prüfe unter Windows in der Windows Defender Firewall → *App zulassen*, ob `VDTerminal` für
  **private** Netzwerke angehakt ist.

**Das Dashboard lädt, zeigt aber keine Daten**

1. **Ist der Export an?** Im Spiel unter *Einstellungen → Allgemeine Einstellungen* den Eintrag
   **VDTelemetry** suchen und prüfen, ob **Export aktiviert** eingeschaltet ist.
2. **Ist FS25_additionalInputs installiert und aktiviert?** Ohne diese Mod schaltet sich VDTelemetry
   stillschweigend ab — eine Meldung im Spiel gibt es dafür noch nicht. Such in der `log.txt` des
   Spiels nach: `FS25_additionalInputs is required but not present`.
   Die Logdatei liegt bei den Spielständen im Ordner `FarmingSimulator2025`.
3. **Hat VDTerminal deinen Spielordner gefunden?** Schau in der Konsole nach:
   ```text
   Game directory not found: C:\Users\du\Documents\My Games\FarmingSimulator2025
   ```
   Wenn das dort steht, musst du den Ordner selbst angeben (siehe unten).

**VDTerminal den Spielordner mitteilen**

Gesucht ist der Ordner, in dem `modSettings`, `mods` und `savegame1` liegen.

- **Windows** — leg neben `VDTerminal.exe` eine Textdatei namens `start.bat` an, mit diesem Inhalt:
  ```bat
  set VDT_GAME_DIR=D:\Irgendwo\FarmingSimulator2025
  VDTerminal.exe
  ```
  Starte ab dann `start.bat` statt der `.exe`.
- **Linux** —
  ```bash
  VDT_GAME_DIR="/pfad/zu/FarmingSimulator2025" ./VDTerminal/bin/VDTerminal
  ```

## Einstellungen, die sich lohnen

Im Spiel unter *Einstellungen → Allgemeine Einstellungen → VDTelemetry*:

- **Export aktiviert** — der Hauptschalter. Beim Ausschalten werden die exportierten Dateien
  gelöscht, damit das Terminal erkennt, dass der Export gestoppt wurde, statt alte Daten zu zeigen.
- **Schreibintervall** — wie oft die Fahrzeugdaten geschrieben werden. 100 ms läuft am flüssigsten;
  erhöhe den Wert, wenn seltener geschrieben werden soll. Bei 100 ms sind das rund 140 MB pro Stunde
  — wenig im Vergleich zu dem, wofür eine moderne SSD ausgelegt ist. Wenn du trotzdem lieber gar
  nicht auf die Platte schreiben möchtest, stehen in der Readme der Mod
  [Anleitungen, den Ordner in den Arbeitsspeicher zu legen](https://github.com/VertexDezign/VDTelemetry/blob/main/vdTelemetry/Readme.md#keeping-telemetry-writes-off-the-ssd-optional)
  — für Linux und Windows.
- **Leistungsprofil** — wie oft *alles andere* (Karte, Produktionen, Tiere, …) aktualisiert wird.
  **Niedrig** schaltet zusätzlich die Bodenkarten-Overlays komplett ab, die mit Abstand am meisten
  Leistung kosten. Fang mit **Hoch** an und geh runter, wenn das Spiel ruckelt.

Alles Weitere stellst du im Terminal selbst ein oder in
`modSettings/FS25_vdTelemetry/vdTelemetrySettings.xml`.

## Das hier ist eine Alpha — bitte lesen

- **Die Programme sind nicht signiert.** Windows SmartScreen und manche Virenscanner melden sich bei
  jeder neuen unsignierten Anwendung. Genau das bedeutet die Warnung — nicht mehr.
- **Gib Port 3001 nicht ins Internet frei.** Das Dashboard hat kein Passwort und ist nur für dein
  eigenes Netzwerk gedacht. Richte im Router keine Portweiterleitung darauf ein.
- **Das Dashboard gibt es nur auf Englisch.** Die Einstellungen der Mod im Spiel sind übersetzt, die
  Texte des Terminals bisher nicht.
- **Mehrspieler:** Die Telemetrie wird auf deinem eigenen PC geschrieben. Wer ein Dashboard möchte,
  lässt also seine eigene Kopie laufen. Auf einem Dedicated Server ist nichts zu installieren.
- Einen Fehler gefunden, oder passt etwas in dieser Anleitung nicht zu dem, was du siehst? Dann
  bitte ein Issue aufmachen unter <https://github.com/VertexDezign/VDTelemetry/issues>.
