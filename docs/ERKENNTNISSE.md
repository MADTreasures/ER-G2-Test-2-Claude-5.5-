# Erkenntnisse aus dem Test-Build – Grundlage für die richtige App

Dieses Dokument sammelt, was die Test-App „G2 Direct“ über die direkte Verbindung Pixel Watch ↔ Even Realities G2 gezeigt hat, und trennt dabei klar:

- ✅ **auf Hardware bestätigt**: mit Pixel Watch 5 (groß) und G2 ausprobiert
- 🧪 **nur simuliert**: in Tests und Simulation geprüft, auf Hardware noch offen
- ❓ **offen**: noch nicht untersucht

Die Einzelheiten des Protokolls (Byte-Formate, Befehle, Quellen) stehen in [PROTOKOLL.md](PROTOKOLL.md). Hier geht es darum, was davon trägt, was man beachten muss und welche Entscheidungen man übernehmen sollte.

Stand: Version 0.2.1. Erster Hardware-Test mit Version 0.1.0.

---

## 1. Grundsätzliches

| Erkenntnis | Status |
|---|---|
| Die Uhr kann die G2 **ohne Smartphone** direkt per Bluetooth LE ansteuern | ✅ |
| Es gibt dafür **kein offizielles Protokoll**. Das Even Hub SDK läuft nur als Web-App in der Even-App auf dem Handy. Man ist auf das von der Community entschlüsselte Protokoll angewiesen (MentraOS u. a.). | ✅ |
| Nach einem **Firmware-Update der Brille** kann sich das Protokoll ändern. Die Test-App ist dann das Werkzeug, um das zu prüfen. | Risiko |
| Koppeln allein zeigt nichts an. Erst Anmeldung + Seitenaufbau über GATT bringen etwas auf die Brille. Darum zeigte der alte Prototyp nichts. | ✅ |

## 2. Verbindung

| Erkenntnis | Status |
|---|---|
| Die G2 erscheint als zwei BLE-Geräte (`Even G2_<nn>_L_…`, `…_R_…`). Die Nummer `<nn>` gehört zum Paar, die Seriennummer steckt in den Herstellerdaten der Werbung. | ✅ |
| Der **rechte Bügel** ist der Kommandokanal: Alle Anzeige-Befehle (EvenHub) gehen nur an rechts. Links bekommt nur Anmeldung und Uhrzeit. | ✅ |
| Das **Smartphone muss getrennt sein** (Bluetooth aus oder Even-App beendet), sonst ist die Brille belegt (typisch: Fehler 133). | ✅ Vorbereitung beim Test |
| Anmeldung, Rollenwechsel, Uhrzeit, Onboarding-Ende, Gesten-Registrierung, Geräteinfo – Reihenfolge und 200 ms Abstände wie MentraOS funktionieren. | ✅ |
| Akku und Firmware-Version der Brille kommen über die Geräteinfo-Abfrage. | ✅ (Akku 100 % angezeigt) |
| Ohne Heartbeats (alle 5 s) beendet die Firmware die Seite nach ~10 s. | 🧪 aus Quellen, App sendet sie |
| Die Firmware verlangsamt die Verbindung nach ~60 s ohne Verkehr. Die App fordert bei jeder Berührung (höchstens alle 20 s) wieder die schnelle Verbindung an. | 🧪 aus Quellen |
| Zeigt die linke Linse das Bild auch ohne verbundenen linken Bügel? | ❓ |
| Verhalten über lange Zeit (Stunden), mit Bildschirm aus, nach Etui/Aufladen | ❓ |

Android-Stolperfallen, die im Code (`ble/G2Link.kt`) gelöst sind und in der richtigen App gleich bleiben sollten:
- MTU 247 **vor** der Dienstsuche anfordern, Pakete ≤ 244 Byte
- Benachrichtigungen explizit per CCCD einschalten
- GATT-Vorgänge streng nacheinander, abgelehnte Schreibvorgänge wiederholen statt verwerfen
- Pakete verschiedener Nachrichten nie mischen
- vor `connectGatt` den Scan stoppen, immer `gatt.close()`
- Die G2 hat eine zufällig-statische Adresse. Gemerkte Adressen über Scan, dann gekoppelte Geräte, dann `getRemoteLeDevice(…, ADDRESS_TYPE_RANDOM)` auflösen.
- Fehler 133/62/147/8 sind oft vorübergehend: ein zweiter Versuch nach 1,5 s hilft.

## 3. Anzeige

| Erkenntnis | Status |
|---|---|
| Anzeigefläche **576 × 288 px**, grün, Graustufen 4 Bit | ✅ |
| Seite anlegen mit CREATE, ändern mit REBUILD. CREATE wird nur angenommen, solange keine Seite registriert ist, sonst REBUILD verwenden. | ✅ |
| **Grenzen pro Seite:** höchstens 12 Container, davon höchstens **8 Text-** und **4 Bildcontainer**, genau einer mit `isEventCapture`, Namen ≤ 14 Zeichen | 🧪 aus Firmware-Analyse, eingehalten |
| Container mit Rahmen (Breite, Farbe, Radius) und Innenabstand funktionieren | ✅ |
| **Bilder** als 4-Bit-BMP mit Header, Fragmente ≤ 3800 Byte, 1 s nach Seitenaufbau warten, jedes Bild zweimal senden | ✅ Graukeil erscheint |
| **Bilder liegen immer über Text.** Der Cursor verschwindet über Bildern. | 🧪 aus Quellen |
| **Text-Updates** sind klein und flimmerfrei. Die Brille bestätigt jedes nach **Ø 141 ms**. | ✅ gemessen |
| Die Firmware verwirft **führende ASCII-Leerzeichen**. Einrücken mit **U+00A0** (geschütztes Leerzeichen, 5 px) funktioniert. | ✅ |
| Zeilenhöhe 27 px. Glyphenbreiten aus `@evenrealities/pretext` stimmen (Titelzeile passt). | ✅ |
| Die Schrift kennt ASCII, Umlaute, ß, `» « „ “ – · ━ ┃ ╋ ◎ █`, aber **keine Pfeile** (→ ▶ ►), kein ● ○ × ✕ | aus pretext, ❓ auf Hardware nur teilweise gesehen |
| Seitenwechsel per REBUILD (Fenster öffnen/schließen) | 🧪 |

## 4. Cursor und Bedienung

| Erkenntnis | Status |
|---|---|
| **Cursor aus Textebenen:** Container lassen sich ohne REBUILD nicht verschieben. Stattdessen steht das Fadenkreuz `╋` in transparenten, bildschirmbreiten Textebenen und wird per Einrückung (5 px) und Ebenenwahl (4 Ebenen, ~6,75 px) positioniert. | ✅ (mit 5 Ebenen) |
| Jede Ebene hat immer dieselbe Byte-Länge. Damit ist egal, wie die Firmware das Längenfeld deutet. | ✅ funktioniert |
| Beim Ebenenwechsel zuerst neue Ebene zeichnen, dann alte leeren (kurz zwei Cursor statt keiner) | ✅ |
| **Auf jede Bestätigung warten ergibt nur ~7 Bewegungen/s → der Cursor ruckelt.** | ✅ beobachtet |
| Mehrere Updates gleichzeitig (Standard 4, Menü „Parallel“ 1–8): 19,5 Bewegungen/s in der Simulation | 🧪 → **Testwert eintragen** |
| Relatives Touchpad mit Beschleunigung: kein Sprung beim Neuaufsetzen | ✅ |
| Bewegungen unterhalb der Tipp-Schwelle zurückhalten, damit ein Tipp den Cursor nicht verschiebt | 🧪 |
| Doppeltipp (≤ 400 ms, je Tipp ≤ 300 ms) = Klick; Halten 0,9 s = Menü | 🧪 |
| Hover-Markierung von Feldern per Text-Update (» « statt geschützter Leerzeichen, gleiche Byte-Länge) | 🧪 |
| Wischen nach rechts darf die App nicht schließen (`windowSwipeToDismiss=false`) | ✅ |

## 5. Empfohlene Standardwerte

| Wert | Einstellung | Herkunft |
|---|---|---|
| MTU | 247, Pakete ≤ 244 B, 7 ms Abstand | Quellen, funktioniert |
| Heartbeat | 5 s (EvenHub + Geräteeinstellungen, nur rechts) | Quellen |
| Updates gleichzeitig | 4 | Simulation, **Testwert ausstehend** |
| Mindestabstand zwischen Updates | 40 ms (≤ 25/s) | Annahme |
| Text-Update gilt als verloren nach | 600 ms | aus Ø 141 ms abgeleitet |
| Festtakt, falls keine Bestätigungen kommen | 16/s | Quellen (Spiele laufen mit 12–20 fps) |
| Warten auf Seitenaufbau-Antwort | 3 s | Annahme |
| Wiederverbinden | bis 3×, Abstand 2/4/6 s | Annahme |
| Touchpad | Grundverstärkung 2,6 px/dp, Beschleunigung 0,55–2,6× | nach Gefühl, Test ausstehend |

## 6. Entwicklungsumgebung

- Aktuell: Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, SDK 37, Wear Compose 1.7.0.
- **Gradle 8.x läuft nicht mit Java 25.** Das war der Stolperstein beim ersten Öffnen in Android Studio. Mit Gradle ≥ 9.1 kein Problem.
- AGP 9 bringt Kotlin selbst mit, das Plugin `org.jetbrains.kotlin.android` entfällt.
- Installation auf die Uhr per WLAN-ADB aus Android Studio (▶) funktioniert. APKs mit unterschiedlicher Debug-Signatur können sich nicht überschreiben: vorher `adb uninstall ch.madtreasures.g2direct`.
- Log mitschneiden: `adb logcat -v time -s G2Direct:V > g2direct-log.txt`.

## 7. Was die richtige App anders braucht

- **Vordergrund-Dienst** für die Verbindung. Die Test-App hält den Bildschirm an und läuft sonst nur, solange Wear OS sie nicht beendet.
- **Oberflächen-Bausteine für die Brille**: Buttons, Listen, Fenster, Cursor als wiederverwendbare Komponenten über einer allgemeinen Schnittstelle („Seite anzeigen“, „Text ändern“, Ereignisse) statt einer fest verdrahteten Testseite.
- **Container-Budget planen**: 8 Textcontainer pro Seite, davon gehen 1 (Ereignisse) + 4 (Cursor) weg, bleiben 3 für Inhalte. Für mehr Inhalt: mehrere Elemente in einem Textcontainer zusammenfassen oder Bilder nutzen (dann ohne Cursor darüber).
- **Energie**: Heartbeats, Verbindungspriorität und Bildschirm-an kosten Akku auf beiden Seiten; messen.
- **Kern wiederverwenden**: `protocol/`, `ble/` und der Simulator `FakeGlasses` als Modul `g2-core` herauslösen, damit Test-App und richtige App denselben, getesteten Code nutzen.

## 8. Testergebnisse

Hier die Ergebnisse jedes Hardware-Tests eintragen (Datum, Version, Beobachtung, Log-Datei).

| Datum | Version | Test | Ergebnis |
|---|---|---|---|
| 2026-09-24 | 0.1.0 | Verbinden, Testbild, Graukeil, Cursor | ✅ alles sichtbar, Akku 100 %, Ø 141 ms Antwortzeit, Cursor ruckelt (~7/s) |
| | 0.2.1 | Parallel-Wert (1/4/6/8): Flüssigkeit, Ø ms | |
| | 0.2.1 | Feld zeigen (» «), Doppeltipp, Fenster, Schließen | |
| | 0.2.1 | Einzeltipp verschiebt Cursor nicht | |
| | 0.2.1 | Akku-Zeile Uhr/Brille | |
| | 0.2.1 | Beide Linsen? Stabil über 5+ Minuten? | |
