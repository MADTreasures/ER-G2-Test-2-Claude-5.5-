# G2 Direct

G2 Direct ist eine Test-App für Wear OS. Die Pixel Watch verbindet sich per Bluetooth LE **direkt** mit der Even Realities G2, zeigt ein Testbild auf der Brille und bewegt dort ein Fadenkreuz. Das Uhr-Display dient dabei als relatives Touchpad. Im Betrieb ist **kein Smartphone** beteiligt: Die Uhr rechnet und steuert, die Brille zeigt nur an.

> **Ehrlicher Stand:** Die App ist vollständig implementiert und automatisch getestet, lief aber **noch nie auf einer echten G2 oder Pixel Watch 5**. In der Entwicklungsumgebung gab es weder Brille noch Uhr. Der erste Test mit Hardware passiert also bei dir. Dafür gibt es unten eine [Checkliste](#8-erster-test-auf-echter-hardware).

---

## 1. Welche Schnittstellen es gibt – und welche die App nutzt

| Weg | Ohne Smartphone? | Bewertung |
|---|---|---|
| **Even Hub SDK** (offiziell) | nein | Apps laufen als Web-App im WebView der Even-App auf dem Smartphone. Das Smartphone leitet an die Brille weiter. Das widerspricht der Vorgabe. |
| **BLE-Protokoll der Even-App** (inoffiziell, von der Community entschlüsselt) | **ja** | Wird von MentraOS produktiv auf Android eingesetzt. Ein ESP32-Projekt (AtomS3) zeigt Text auf der G2 ganz ohne Smartphone. **Diese App nutzt diesen Weg.** |
| Eigene Firmware (openCFW) | – | Nur als Nachschlagewerk genutzt, nichts wird auf die Brille geflasht. |

Even Realities dokumentiert **kein** Protokoll für eine direkte Verbindung. Die Einzelheiten und alle Quellen stehen in [docs/PROTOKOLL.md](docs/PROTOKOLL.md).

**Warum das manuelle Koppeln beim alten Prototyp nichts angezeigt hat:** Eine Bluetooth-Kopplung allein bringt nichts auf die Brille. Die Brille zeigt erst etwas an, wenn ein Programm über GATT das Anmelde- und Seitenprotokoll spricht:

1. Anmeldung an beiden Bügeln
2. Kommandorolle für den rechten Bügel
3. Testseite anlegen
4. Inhalte aktualisieren
5. Heartbeats senden

Genau das macht diese App.

```
Pixel Watch (App „G2 Direct“)
 ├─ BLE ─► rechter Bügel: Anmeldung, Zeit, Testseite, Cursor-Updates, Bild, Heartbeats
 └─ BLE ─► linker Bügel:  Anmeldung, Zeit (optional – ohne ihn läuft die App mit Warnung weiter)
```

## 2. Stand: implementiert – getestet – auf Hardware unbestätigt

- **Implementiert** heißt: Der Code ist vorhanden und wird gebaut.
- **Getestet** heißt: automatisch geprüft, aber **ohne echte Brille und Uhr**. Dazu gehören:
  - Unit-Tests
  - eine Simulation gegen ein Modell der Brille (`FakeGlasses`). Es bildet das dokumentierte Firmware-Verhalten nach und meldet jeden Regelverstoß, z. B. EvenHub-Nachrichten an den linken Bügel, zu viele Container, falsche Längen oder ungültige BMP-Dateien.
  - UI-Tests unter Robolectric, die künstliche Touch-Ereignisse durch den echten Gesten-Code der App schicken
  - gerenderte Screenshots
- Für einige Tests gab es **Mutationsproben**: Absichtlich eingebaute Fehler müssen die Tests scheitern lassen, und das taten sie.

| Funktion | Implementiert | Getestet (ohne Hardware) | Auf echter Hardware |
|---|---|---|---|
| Berechtigung „Geräte in der Nähe“, Bluetooth einschalten | ✅ | Screenshot | unbestätigt |
| Brille suchen: BLE-Scan **plus** bereits gekoppelte und vom System verbundene Bügel | ✅ | Unit-Tests (Namen, Seriennummer, Gruppierung); Robolectric-Test: gekoppelte und verbundene Bügel bleiben über mehrere Scans gelistet | unbestätigt |
| Linken und rechten Bügel zu **einer** Brille zusammenfassen, auch wenn ein gekoppelter Bügel nicht sendet | ✅ | Unit-Tests | unbestätigt |
| Zuletzt verwendete Brille ohne neuen Scan verbinden | ✅ | – | unbestätigt |
| GATT-Verbindung je Bügel: MTU 247, Dienstsuche, Benachrichtigungen, Schreib-Warteschlange | ✅ | nur kompiliert, der Teil braucht echtes Bluetooth | unbestätigt |
| Rahmenformat, CRC, Zerlegen und Zusammensetzen, Antworten dekodieren | ✅ | 13 Unit-Tests, u. a. CRC-Referenzwert und Antworten, die MentraOS mitgeschnitten hat | unbestätigt |
| Anmeldung und Start-Sequenz (Reihenfolge und Abstände wie MentraOS) | ✅ | Simulation | unbestätigt |
| Testbild: Rahmen, Titel- und Infozeile, Kalibrier-Kasten | ✅ | Simulation (Limits, Rückfall von CREATE auf REBUILD) und Layout-Vorschau | unbestätigt |
| Graukeil als 4-Bit-Bild (Bildkanal) | ✅ | Unit-Tests (BMP-Aufbau) und Simulation | unbestätigt |
| Fadenkreuz-Cursor über fünf Textebenen | ✅ | Unit-Tests und Simulation mit beiden möglichen Firmware-Lesarten, Mutationsproben | unbestätigt – besonders, ob die Firmware die Einrückung mit U+00A0 darstellt |
| Relatives Touchpad: kein Sprung beim Neuaufsetzen, gleiche Bewegung ergibt überall gleichen Weg, Halten öffnet das Menü | ✅ | 4 UI-Tests mit künstlichen Touch-Ereignissen, Mutationsprobe | Gefühl und Tempo auf der Uhr unbestätigt |
| Tempo über die Krone (0,3× bis 4×) | ✅ | – | unbestätigt |
| Taktung mit Bestätigungen, Rückfall auf Festtakt, wenn keine kommen | ✅ | Simulation | erreichbare Rate unbekannt |
| Heartbeats, Neuaufbau nach dem Schließen der Seite durch die Brille | ✅ | Simulation | unbestätigt |
| Neu verbinden (bis zu 3×), Weiterlaufen ohne linken Bügel | ✅ | Simulation | unbestätigt |
| Status und Fehlermeldungen auf der Uhr | ✅ | Screenshots auf zwei runden Displaygrößen (384 und 454 px) | Lesbarkeit unbestätigt |
| Protokoll-Ansicht auf der Uhr, `logcat` | ✅ | Screenshot | – |

**Offene Fragen, die nur der Test mit Hardware klärt:**

1. Nimmt die Brille die Verbindung der Uhr an, wenn das Smartphone getrennt ist? Erscheint ein Kopplungsdialog?
2. Bestätigt der rechte Bügel die Anmeldung? Die App läuft auch ohne Bestätigung weiter, meldet das aber.
3. Nimmt die Firmware die Testseite an?
4. Bestätigt die Brille Text-Updates? Wenn nicht, schaltet die App auf einen festen Takt von 16 Updates pro Sekunde.
5. Stellt die Firmware die Einrückung mit geschützten Leerzeichen (U+00A0) dar? Wenn nicht, klebt der Cursor am linken Rand. Das würdest du sofort sehen.
6. Sitzt das Fadenkreuz beim Start **mittig** im Kasten? Das prüft die Glyphen-Maße.
7. Zeigen beide Linsen das Bild, auch wenn der linke Bügel nicht verbunden ist?
8. Welche Update-Rate und Verzögerung schafft die Uhr?

## 3. Vorbereitung

1. Brille laden und **aus dem Etui** nehmen.
2. **Smartphone trennen:** Bluetooth am Smartphone ausschalten oder die Even-App beenden. Solange die Even-App verbunden ist, ist die Brille in der Regel belegt. Dann findet die Uhr sie nicht, oder die Verbindung scheitert mit Fehler 133. Nach dem Test kannst du Bluetooth am Smartphone wieder einschalten.
3. Den **alten Prototyp** auf der Uhr beenden, am besten deinstallieren, damit keine zweite App die Brille belegt.
4. Koppeln in den Uhr-Einstellungen ist **nicht nötig**. Bereits gekoppelte Bügel erscheinen trotzdem in der Liste. Zeigt die Uhr beim Verbinden einen Kopplungsdialog, bestätige ihn.
5. Wenn gar nichts geht: Brille neu starten. Laut [men-g2-ble-gateway](https://github.com/gpsnmeajp/men-g2-ble-gateway) tippst du dazu 5× schnell auf beide Touchflächen.

## 4. Bauen und installieren

Voraussetzungen:
- Android Studio mit Android SDK Platform 36
- JDK 17 oder neuer (das in Android Studio enthaltene JBR genügt)
- Die Uhr ist per WLAN-ADB verbunden.

Die App läuft ab Wear OS 3 (API 30).

**Android Studio (Mac)**

1. Repository holen und den Branch auschecken:
   ```bash
   git clone https://github.com/MADTreasures/ER-G2-Test-2-Claude-5.5-.git
   cd ER-G2-Test-2-Claude-5.5-
   git checkout claude/zen-newton-15o9rd
   ```
2. In Android Studio *File → Open* wählen und den Ordner öffnen. Den Gradle-Sync abwarten. Fehlt die SDK Platform 36, bietet Android Studio die Installation an.
3. Oben die Uhr als Gerät und die Konfiguration **app** wählen, dann ▶ *Run*.

**Kommandozeile**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
adb devices                      # die Uhr muss als "device" erscheinen
./gradlew :app:installDebug      # bei mehreren Geräten: ANDROID_SERIAL=<ip:port> voranstellen
adb shell am start -n ch.madtreasures.g2direct/.MainActivity
```

- Die App heißt **G2 Direct**, das Paket `ch.madtreasures.g2direct`.
- Die Debug-APK liegt nach dem Bauen unter `app/build/outputs/apk/debug/app-debug.apk`.

## 5. Bedienung

| Bildschirm | Was du siehst und tun kannst |
|---|---|
| **Berechtigung** | Beim ersten Start „Geräte in der Nähe“ erlauben |
| **Brille wählen** | Jede G2 erscheint als **ein** Eintrag mit beiden Bügeln („L ✓ R ✓“), dazu Signalstärke oder „gekoppelt“. Oben steht „zuletzt verwendet“. **Suchen** startet einen neuen Scan von 25 s. |
| **Status** | Fortschritt je Bügel („verbinde…“, „richte ein…“, „verbunden ✓“), Phase, Anzeige-Status. Fehler stehen rot, Warnungen orange. Während des Aufbaus gibt es **Abbrechen**, nach einem Fehler **Erneut** und **Andere Brille**, immer **Protokoll**. |
| **Touchpad** | Öffnet sich automatisch, sobald alles bereit ist. |
| **Menü** | Akku, Firmware und Zähler. Außerdem: *Testbild neu senden*, *Cursor zentrieren*, Cursor-Form (Fadenkreuz / Fadenkreuz groß / Ring), Tempo, *Protokoll*, *Trennen* |

Bedienung des Touchpads:
- **Finger irgendwo aufsetzen und bewegen:** Der Cursor bewegt sich relativ. Abheben und woanders neu aufsetzen bewegt ihn nicht.
- **Langsame Bewegung** positioniert fein, **schnelle** Bewegung überquert die Anzeige.
- **Krone drehen** ändert das Tempo.
- **Finger etwa 1 s ruhig halten** öffnet das Menü, die Uhr vibriert dabei.
- Es gibt keine Pfeiltasten und keine Zonen. Die Texte auf dem Touchpad sind nur Anzeige:
  - Punkte für den linken und rechten Bügel, Akku
  - Cursor-Position
  - „Anzeige: aktiv/unbestätigt/…“
  - Updates pro Sekunde und durchschnittliche Antwortzeit der Brille
  - letzte Eingabe an der Brille, z. B. Tippen am Bügel

Weitere Hinweise:
- **Wischen nach rechts schließt die App nicht.** Sonst würde jede Cursorbewegung nach rechts sie beenden.
- Zum Verlassen drückst du die Krone. **Trennen** im Menü schließt die Testseite auf der Brille und beendet die Verbindung.
- Während einer Sitzung bleibt das Uhr-Display an. Das kostet Akku.
- Die App hat keinen Hintergrunddienst. Läuft sie länger im Hintergrund, kann Wear OS sie beenden.

| Brille wählen | Verbindung | Touchpad | Menü |
|---|---|---|---|
| ![Brille wählen](docs/screenshots/01_brille_waehlen.png) | ![Verbindungsaufbau](docs/screenshots/03_verbindung.png) | ![Touchpad](docs/screenshots/05_touchpad.png) | ![Menü](docs/screenshots/07_menue.png) |

*Die Bilder sind unter Robolectric auf einem simulierten runden Display gerendert, es sind keine Fotos der Uhr.* Weitere Bilder liegen in [docs/screenshots](docs/screenshots): Fehler, Warnung, Protokoll, kleines Display.

## 6. Was du auf der Brille sehen solltest

![Simulation des Testbilds](docs/screenshots/brille_simulation_start.png)

*Das ist eine Layout-Simulation aus dem Protokollmodell (grobe Ersatzschrift), **kein Foto der Brille**.*

Auf der Brille erscheinen:
- ein Rahmen um die ganze Anzeigefläche (576 × 288)
- oben die Titelzeile `G2 Direct · Testbild` mit Cursor-Position und Update-Zähler
- in der Mitte ein 80 × 80-Kasten, in dem das Fadenkreuz beim Start mittig stehen sollte
- unten ein Graukeil mit 16 Stufen

Das ist normal:
- Über dem Graukeil verschwindet der Cursor, weil die Firmware Bilder über Text zeichnet.
- Der Cursor bewegt sich in Schritten von 5 px waagrecht und etwa 5,4 px senkrecht. Die Standard-Firmware kann Text nicht frei positionieren. Warum der Cursor aus Textebenen besteht, steht in [docs/PROTOKOLL.md](docs/PROTOKOLL.md#5-der-cursor--warum-textebenen).

## 7. Fehlersuche

Die App zeigt Fehler auf der Uhr im Klartext. Das Protokoll im Menü bzw. auf der Statusseite enthält alle Einzelheiten.

| Meldung auf der Uhr | Bedeutung | Was tun |
|---|---|---|
| „Keine G2 gefunden …“ | kein Bügel sendet, keiner ist gekoppelt | Brille aus dem Etui nehmen, Smartphone-Bluetooth ausschalten, **Suchen** |
| „rechter Bügel fehlt – er trägt die Anzeige“ | nur der linke Bügel ist sichtbar | erneut suchen; der rechte Bügel hängt eventuell noch am Smartphone |
| „… 133 (GATT_ERROR – Brille belegt oder nicht erreichbar)“ | Sammelfehler von Android, meist hält ein anderes Gerät die Verbindung | Smartphone-Bluetooth aus, alte App beenden, **Erneut**. Die App versucht es einmal selbst neu. |
| „… 8 (Verbindungs-Timeout …)“, „147“, „Keine Verbindung nach 20 s“ | Brille nicht erreichbar | näher heran, Brille aufsetzen oder aufwecken |
| „… 5“ bzw. „… 15 (… Kopplung prüfen)“ | die Brille verlangt eine Kopplung | Kopplungsdialog bestätigen. Hilft das nicht: Kopplung in den Uhr-Einstellungen entfernen und neu verbinden. |
| „G2-Kanal 5401/5402 nicht gefunden …“ | falsches Gerät oder geänderte Firmware | Protokoll schicken |
| „Keine Anmelde-Bestätigung vom rechten Bügel – versuche trotzdem weiter“ | Warnung | beobachten, ob das Testbild trotzdem erscheint |
| „Brille hat den Seitenaufbau nicht bestätigt – Anzeige prüfen“ | Seite gesendet, keine Antwort | Brille ansehen, Menü → *Testbild neu senden* |
| „Seitenaufbau abgelehnt: …“ | die Firmware lehnt das Layout ab | Protokoll schicken |
| „Brille bestätigt Text-Updates nicht – Festtakt 16/s“ | Hinweis, kein Fehler | Cursor trotzdem bewegen |
| „Testbild geschlossen – Touchpad berühren zum Neuaufbau“ | die Brille hat die Seite beendet, z. B. durch eine Geste am Bügel | Touchpad berühren |
| „Testbild im Hintergrund – Touchpad berühren zum Zurückholen“ | ein anderes Brillen-Menü liegt davor | Touchpad berühren |
| „Linker Bügel nicht verbunden … – weiter nur mit rechts“ | links fehlt | zeigt die linke Linse trotzdem etwas? Bitte notieren. |
| „Verbindung verloren – neuer Versuch n/3“ | Verbindung abgebrochen | die App verbindet bis zu 3× neu |
| „Zu viele Scans in kurzer Zeit – 30 s warten“ | Grenze von Android (5 Scans in 30 s) | kurz warten |

## 8. Erster Test auf echter Hardware

Bitte in dieser Reihenfolge vorgehen und die Beobachtungen notieren:

1. App installieren und starten, Berechtigung erlauben.
2. Erscheint die Brille **als ein Eintrag** mit „L ✓ R ✓“?
3. Eintrag antippen: Welche Schritte zeigt die Statusseite? Erscheint ein Kopplungsdialog? Gibt es Warnungen?
4. Erscheint das Testbild? Sind Rahmen, Titelzeile, Kasten und Graukeil zu sehen? Auf beiden Linsen?
5. Steht das Fadenkreuz beim Start **mittig im Kasten**?
6. Cursor bewegen:
   - Folgt er flüssig?
   - Erreicht er alle Ränder?
   - Bleibt er beim Neuaufsetzen stehen?
   - Wie verändert sich das Tempo mit der Krone?
7. Welche Werte zeigt das Touchpad unter „Anzeige“, z. B. „20/s · Ø 38 ms“? Steht dort „Festtakt“?
8. Etwa 5 Minuten verbunden lassen: Bleibt die Verbindung stabil?
9. Menü → *Trennen*: Verschwindet das Testbild?

**Was du mir zurückschicken kannst:**
- einen Screenshot der Protokoll-Seite
- besser noch das vollständige Log vom Mac aus:
  ```bash
  adb logcat -c
  adb logcat -s G2Direct:V > g2direct-log.txt     # während des Tests laufen lassen, danach Ctrl+C
  ```

Mit diesem Log lassen sich Abweichungen der Firmware gezielt beheben.

## 9. Projektaufbau und Tests

```
app/src/main/java/ch/madtreasures/g2direct/
  protocol/  Rahmen, CRC, Protobuf, Nachrichten, BMP, Testbild, Cursor-Ebenen (reines Kotlin)
  ble/       Scan und Gruppierung, GATT-Verbindung je Bügel, Sitzungslogik (SessionEngine), Android-Anbindung
  ui/        Bildschirme mit Compose for Wear OS
app/src/test/java/ch/madtreasures/g2direct/
  protocol/  Unit-Tests
  ble/       Namen, Gruppierung, Scanner
  sim/       FakeGlasses (Brillen-Modell), Sitzungs-Simulation, Layout-Vorschau
  ui/        Touchpad-Gesten, Screenshots
docs/        PROTOKOLL.md, screenshots/
```

```bash
./gradlew :app:testDebugUnitTest                  # alle Tests (der erste Lauf lädt die Robolectric-Laufzeit, ca. 100 MB)
./gradlew :app:testDebugUnitTest -Pscreenshots    # zusätzlich docs/screenshots neu erzeugen
./gradlew :app:lintDebug
```

Technik:

| Bereich | Version |
|---|---|
| Build | AGP 8.13.2, Gradle 8.14.3 |
| Sprache und UI | Kotlin 2.3.21, Compose for Wear OS (Material 3) 1.6.2 |
| SDK | compileSdk und targetSdk 36, minSdk 30 |

`libs.versions.toml` erklärt, warum nicht die allerneuesten AndroidX-Versionen verwendet werden.

## 10. Grenzen und Hinweise

- Das Protokoll ist inoffiziell. Ein Firmware-Update der G2 kann es ändern.
- Die App sendet nur Anmelde-, Zeit-, Seiten- und Heartbeat-Nachrichten. Einstellungen der Brille (Dashboard, „Hey Even“, Head-Up) ändert sie **nicht**.
- Dieses Projekt steht in keiner Verbindung zu Even Realities.
- Quellen und Lizenzen: [docs/PROTOKOLL.md](docs/PROTOKOLL.md#7-quellen) und [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
