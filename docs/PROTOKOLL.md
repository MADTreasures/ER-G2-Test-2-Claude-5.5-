# G2-BLE-Protokoll – was die App sendet und warum

Dieses Dokument beschreibt die Kommunikation zwischen Uhr und Brille, wie sie in `app/src/main/java/ch/madtreasures/g2direct/protocol/` und `ble/SessionEngine.kt` umgesetzt ist.

Even Realities veröffentlicht **kein** Protokoll für eine direkte Verbindung. Das offizielle Even-Hub-SDK läuft ausschließlich als Web-App im WebView der Smartphone-App. Alles hier stammt aus Reverse Engineering durch die Community. Die Quellen stehen am Ende. Wo sich Quellen widersprechen, ist das vermerkt.

## 1. Zwei Bügel, zwei BLE-Geräte

| Punkt | Wert |
|---|---|
| Namen | `Even G2_<nn>_L_<6 hex>` (links), `Even G2_<nn>_R_<6 hex>` (rechts) |
| Seriennummer | Herstellerdaten der Werbung, Bytes 0–13 (ASCII), danach 6 Byte MAC (little-endian) |
| Adresstyp | zufällig-statisch (oberste zwei Bits `11`, vom AtomS3-Treiber als Typ 1 gemeldet) |
| Schreiben | Charakteristik `00002760-08c2-11e1-9073-0e8ac72e5401`, Write-Without-Response |
| Benachrichtigungen | `…5402` (Protokoll), zusätzlich `…6402` und `…7402` abonniert (wie Even-App/MentraOS), aber nicht ausgewertet |
| MTU | 247 wird angefordert, Paketgröße ≤ 244 Byte |

- Die Rolle des Kommandokanals liegt beim **rechten** Bügel (`PipeRoleChange`). Alle EvenHub-Nachrichten (Service `0xE0`) gehen nur an den rechten Bügel. Andere Treiber berichten, dass EvenHub-Nachrichten an den linken Bügel die Firmware blockieren oder die Brille neu starten.
- Laut Firmware-Analyse spiegelt die Firmware die Seite auf die linke Linse. Ob das auch klappt, wenn der linke Bügel **nicht** verbunden ist, hat niemand dokumentiert. Die App verbindet deshalb beide Bügel, sofern verfügbar.
- Bekannte Stolperfallen auf Android, die in `ble/G2Link.kt` berücksichtigt sind:
  - MTU vor der Dienstsuche anfordern.
  - CCCD-Deskriptoren explizit schreiben.
  - GATT-Operationen strikt nacheinander ausführen.
  - Abgelehnte Schreibvorgänge wiederholen, statt das Paket zu verwerfen.
  - Pakete verschiedener Nachrichten nie verschachteln.
  - `gatt.close()` immer aufrufen.
  - Vor `connectGatt` den Scan stoppen.

## 2. Rahmenformat

```
AA 21 <sync> <len> <total> <index> <sid> <flags> <payload …> [CRC lo, CRC hi]
```

- `0x21` bedeutet Uhr → Brille. Antworten kommen mit `0x12`.
- `sync` bleibt für alle Pakete einer Nachricht gleich.
- `len` zählt die Bytes nach dem Header (beim letzten Paket inklusive CRC).
- `flags` ist `0x20` für alle Dienste außer Geräteeinstellungen (`0x80`: `0x00`). In Antworten stehen in Bit 1–4 Fehlercodes.
- Die CRC-16/CCITT-FALSE (Poly `0x1021`, Init `0xFFFF`) wird über die gesamte Nutzlast berechnet und little-endian hinter das letzte Paket gehängt.
- Die Nutzlast wird in Stücke von höchstens 236 Byte geteilt. Passt die CRC nicht mehr ins letzte Paket, folgt ein eigenes Paket nur mit der CRC. So wird die MTU nie überschritten; bei MentraOS kann in einem Randfall ein 245-Byte-Paket entstehen.
- Die Nutzlast ist Protobuf. `MagicRandom` wird als uint8 im Bereich 1–255 geführt.

Die Tests prüfen die CRC gegen den Referenzwert `0x29B1` und gegen die MentraOS-Formulierung, dazu Zerlegen und Zusammensetzen bei verschiedenen Größen.

## 3. Anmeldung und Start (wie MentraOS, jeweils 200 ms Abstand)

1. `DevCfg AUTHENTICATION {secAuth=1, phoneType=ANDROID}` → links.
2. Dasselbe → rechts.
3. `PIPE_ROLE_CHANGE {asCmdRole=RIGHT}` → rechts.
4. `TIME_SYNC {Unix-Sekunden + Zeitzonenoffset}` → rechts und links. Die Firmware ignoriert das Zeitzonenfeld.
5. Onboarding `CONFIG {processId=FINISH}` → rechts. Sonst zeigt die Firmware ihr eigenes Onboarding.
6. `gesture_ctrl`-Registrierung `{1:0, 2:magic}` → rechts.
7. Geräteinfo abfragen (`g2_setting` `DEVICE_RECEIVE_REQUEST`, nur lesend) → Akku und Firmware.
8. Bis zu 2,5 s auf `AUTHENTICATION {secAuth=1}` vom rechten Bügel warten. Wie beim AtomS3-Treiber geht es auch ohne Antwort weiter, dann mit Warnung.

Was die App bewusst **nicht** sendet, weil es Einstellungen des Nutzers verändern würde:
- Dashboard-Layout
- „Hey Even“ ausschalten
- Head-Up-Schalter

## 4. EvenHub-Seite (Service `0xE0`)

| Cmd | Richtung | Inhalt |
|---|---|---|
| 0 `CREATE_STARTUP_PAGE` | → | `CreateStartUpPageContainer` in Feld 3: Anzahl, Text-Container (Feld 3), Bild-Container (Feld 4) |
| 1 | ← | `StartupResCmd` (Feld 4), Code 0 = ok |
| 7 `REBUILD_PAGE` | → | gleicher Inhalt in Feld 7 |
| 8 | ← | Code 6 = ok, 7 = abgelehnt |
| 5 `UPDATE_TEXT_DATA` | → | Feld 9 `{1:id, 2:name, 3:offset, 4:length, 5:content}` |
| 6 | ← | Feld 10 Code 8 = ok, 9 = Seite nicht aktiv |
| 3 `UPDATE_IMAGE_RAW_DATA` | → | Feld 5 `{1:id, 2:name, 3:session, 4:gesamt, 5:compress=0, 6:fragment, 7:größe, 8:daten}` |
| 4 | ← | Feld 6 `ImgResCmd`, Code in Feld 8: 4 = ok, 5 = Fehler; pro Fragment |
| 9 / 10 | ↔ | Seite schließen / Bestätigung |
| 12 | → | Heartbeat. Die App sendet alle 5 s einen EvenHub-Heartbeat und einen Heartbeat für die Geräteeinstellungen. Ohne Verkehr beendet die Firmware die Seite nach etwa 10 s |
| 2 | ← | Ereignisse: Tippen, Wischen, Doppeltippen, Vorder-/Hintergrund, Systemende |

- CREATE nimmt die Firmware nur an, solange keine EvenHub-Seite registriert ist. Bleibt die Antwort aus oder kommt eine Ablehnung, versucht die App REBUILD.
- Limits:
  - höchstens 12 Container, davon höchstens 8 Text- und 4 Bildcontainer
  - genau ein Container mit `isEventCapture`
  - Namen mit höchstens 14 Zeichen
  - Startinhalt höchstens 1000, Update höchstens 2000 Zeichen
- Bilder sind **4-Bit-BMP-Dateien mit Header**: `BM`, 40-Byte-DIB, 16 Graustufen, Zeilen von unten nach oben mit 4-Byte-Ausrichtung, das linke Pixel im oberen Nibble.
  - Das deckt sich mit dem dekompilierten Firmware-Decoder.
  - Fragmente sind höchstens 3800 Byte groß.
  - Nach CREATE oder REBUILD wartet die App 1 s. Jedes Bild geht zweimal hinaus, weil g2-kit beobachtet hat, dass der erste Bildstrom nach CREATE manchmal verloren geht.
- Die Firmware zeichnet Bilder **über** Text.

## 5. Der Cursor – warum Textebenen?

Auf der Standard-Firmware lassen sich Container nur per REBUILD verschieben. REBUILD flackert, löscht Bilder und soll bei häufigem Aufruf die Verbindung abbrechen. Bild-Updates kosten jeweils rund 100 ms. Text-Updates dagegen sind klein und laut Community flackerfrei. Die Spiele Pong und Arkanoid laufen genau so mit rund 12 bis 20 Bildern pro Sekunde.

Die Umsetzung steht in `protocol/CursorLayers.kt`:

- **Horizontal:** Die Firmware verwirft führende ASCII-Leerzeichen einer Zeile. Das steht so in Evens eigener Schriftbibliothek `@evenrealities/pretext`. Eingerückt wird deshalb mit **U+00A0** (5 px) → 5-px-Raster.
- **Vertikal:** Die Zeilenhöhe beträgt 27 px. Fünf transparente, bildschirmbreite Textcontainer liegen um 0/5/11/16/22 px versetzt übereinander. Der Cursor steht immer in genau einer Ebene → etwa 5,4-px-Raster.
- **Zeichen:** `╋` (20 × 27 px) als Standard. Zur Auswahl stehen außerdem ein großes Fadenkreuz (`┃`/`━╋━`/`┃`) und `◎`.
- **Konstante Länge:** Jede Ebene enthält immer gleich viele UTF-8-Bytes. Unsichtbare Zellen sind U+3000 (ebenfalls 3 Byte, 20 px), die Zeilen werden mit U+00A0 aufgefüllt. Damit ist gleichgültig, ob die Firmware `contentLength` als „so viele Bytes ersetzen“ (Beobachtung von MentraOS) oder „ganzer Text neu“ (Lesart von faceclaw) auslegt. Die Simulationstests prüfen beide Varianten.
- **Taktung:** Es ist höchstens ein Update gleichzeitig unterwegs; die App wartet auf die Bestätigung (Cmd 6) oder 300 ms. Dazu kommen mindestens 40 ms Abstand, also höchstens etwa 25 Updates pro Sekunde. Dazwischen anfallende Touch-Bewegungen werden zusammengefasst. Schickt die Brille keine Text-Bestätigungen, schaltet die App auf einen festen Takt von 16 pro Sekunde.
- **Wechsel der Ebene:** Zuerst wird die neue Ebene gezeichnet, dann die alte geleert.

Einschränkungen dieser Technik:
- Das Raster ist 5 px (horizontal) bzw. etwa 5,4 px (vertikal) grob.
- Unter dem Graustufen-Bild ist der Cursor verdeckt.
- Das große Fadenkreuz erreicht die oberen und unteren etwa 40 px nicht.

## 6. Testbild

![Simulation](screenshots/brille_simulation_start.png)

Rahmen um die ganze Anzeigefläche, Titel- und Infozeile (Position und Update-Zähler), 80×80-Kasten um den Startpunkt des Cursors, 16-stufiger Graukeil (Bildkanal). *Das Bild ist eine Layout-Simulation aus dem Protokollmodell, keine Aufnahme der Brille.*

## 7. Quellen

| Quelle | Genutzt für |
|---|---|
| [MentraOS `G2.kt`](https://github.com/Mentra-Community/MentraOS) (MIT) | UUIDs, Framing, Start-Sequenz, Protobuf-Feldnummern, BMP-Format, Heartbeats, Ereignisse – produktiv eingesetzter Android-Treiber |
| [g2-kit-unofficial](https://github.com/Commute773/g2-kit-unofficial) (MIT) | aus der Even-App gewonnene `EvenHub.proto`-Definition (Antwortcodes), Stolperfallen (Fragment-`seq`, 4-KB-Fragmente, erster Bildstrom) |
| [@evenrealities/pretext](https://www.npmjs.com/package/@evenrealities/pretext) (MIT, Even Realities) | Glyphenbreiten, Zeilenhöhe 27 px, Verwerfen führender Leerzeichen |
| [ffs-os](https://github.com/yonif8/ffs-os) | Android-Eigenheiten (MTU, CCCD, Busy-Writes, `close()`, Scan vor Connect stoppen), nicht werbende gekoppelte Bügel |
| [men-g2-atoms3-hello](https://github.com/gpsnmeajp/men-g2-atoms3-hello) / [men-g2-ble-gateway](https://github.com/gpsnmeajp/men-g2-ble-gateway) (MIT) | Nachweis, dass die Brille ohne Smartphone angesteuert werden kann; Kopplungshinweise; Adresstyp |
| [even-g2-notes](https://github.com/nickustinov/even-g2-notes) | Limits, Zeitmessungen, Z-Reihenfolge, Glyphenabdeckung |
| [evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW) | Firmware-Dekompilat: BMP-Decoder, Containerlimits, Rolle des rechten Bügels |
| [i-soxi/even-g2-protocol](https://github.com/i-soxi/even-g2-protocol) | Paketaufbau, Dienst-IDs |

Aus MentraOS abgeleitete Konstanten und Abläufe stehen unter der MIT-Lizenz von MentraOS. Glyphenbreiten stammen aus `@evenrealities/pretext` (MIT).
