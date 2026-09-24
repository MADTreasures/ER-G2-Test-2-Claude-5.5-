# Prompt: Recherche zu eigener Firmware für die Even Realities G2

Diesen Text als erste Nachricht in ein neues Claude-Code-Projekt kopieren. Er ist bewusst vollständig, weil die neue Sitzung keinen Kontext aus diesem Projekt hat.

---

Ich möchte herausfinden, ob und wie man für die Datenbrille **Even Realities G2** eine eigene bzw. angepasste Firmware entwickeln kann. Das ist zunächst **reine Recherche**: Bitte nichts flashen, keine Befehle an eine echte Brille schicken und keine Anleitung zum Aufspielen geben, bevor wir Risiken und Wiederherstellungswege verstanden und ich ausdrücklich zugestimmt habe. Antworte auf Deutsch.

## Hintergrund

- Ich habe eine funktionierende Wear-OS-App (Pixel Watch 5), die die G2 **direkt per Bluetooth LE ohne Smartphone** ansteuert: https://github.com/MADTreasures/ER-G2-Test-2-Claude-5.5- (Branch `claude/zen-newton-15o9rd`). Lies dort zuerst `docs/ERKENNTNISSE.md` und `docs/PROTOKOLL.md`.
- Die App nutzt das von der Community entschlüsselte BLE-Protokoll der Even-App (GATT-Dienst mit Charakteristiken `…5401`/`…5402`, Rahmen `AA 21 …` mit CRC-16/CCITT, Protobuf-Nutzlast, EvenHub-Seiten aus höchstens 8 Text- und 4 Bildcontainern).
- Auf echter Hardware bestätigt: Verbindung, Anmeldung, Seitenaufbau, Text- und Bild-Updates. Ein Text-Update wird nach Ø 141 ms bestätigt.
- **Grenzen der Standard-Firmware**, die mich zu eigener Firmware bringen:
  - kein frei positionierbarer Mauszeiger/Sprite; Container lassen sich nur per komplettem Neuaufbau der Seite verschieben
  - höchstens 8 Textcontainer pro Seite, Bilder liegen immer über Text
  - Bild-Updates kosten 100–170 ms, kein direkter Zugriff auf den Framebuffer
  - ~7 bestätigte Updates/s ohne Pipelining

## Bekannte Quellen (Ausgangspunkt, selbst prüfen und ergänzen)

- evenRealities-openCFW (Firmware-Dekompilat, Custom-Firmware-Versuch): https://github.com/kalanihelekunihi/evenRealities-openCFW
- even-g2-notes (Messungen, Limits, Glyphen): https://github.com/nickustinov/even-g2-notes
- MentraOS, Android-Treiber `G2.kt` (MIT): https://github.com/Mentra-Community/MentraOS
- g2-kit-unofficial (Protobuf-Definitionen aus der Even-App): https://github.com/Commute773/g2-kit-unofficial
- i-soxi/even-g2-protocol: https://github.com/i-soxi/even-g2-protocol
- ESP32-Ansteuerung ohne Smartphone: https://github.com/gpsnmeajp/men-g2-atoms3-hello, https://github.com/gpsnmeajp/men-g2-ble-gateway
- Even Hub SDK / offizielle Doku von Even Realities, Foren, Discord-/Reddit-Berichte, FCC-Unterlagen (Chip-Fotos, Funkmodule)

## Fragen, die die Recherche beantworten soll

1. **Hardware**: Welche Chips (Prozessor/SoC, BLE, Display-Treiber, Speicher) stecken in beiden Bügeln? Wie ist die Aufgabenteilung links/rechts? Quellen angeben (Teardown, FCC, Dekompilat).
2. **Firmware-Aufbau**: Betriebssystem/RTOS, Grafik (LVGL?), Aufteilung Bootloader/Anwendung, wie die Bügel miteinander reden.
3. **Update-Weg**: Wie spielt die Even-App Firmware auf (OTA über BLE, DFU-Protokoll, Paketformat)? Ist das Update **signiert/verschlüsselt**, gibt es Secure Boot? Kann man eigene Images überhaupt einspielen?
4. **Stand von openCFW**: Was funktioniert dort wirklich, was ist nur Analyse? Gibt es Berichte über erfolgreich geflashte oder zerstörte („gebrickte“) Brillen?
5. **Wiederherstellung**: Kommt man nach einem fehlgeschlagenen Update zurück zur Original-Firmware (Recovery-Modus, Bootloader, Debug-Schnittstelle SWD/JTAG, Test-Pads)?
6. **Weniger riskante Alternativen**: Gibt es in der Standard-Firmware ungenutzte Befehle (z. B. direkter Framebuffer, Sprites, Container verschieben, schnellere Updates), die ein Großteil meiner Ziele ohne eigene Firmware erreichen?
7. **Vorteile einer eigenen Firmware**: Was wäre damit konkret möglich, was die Standard-Firmware nicht kann, und wie viel bringt es für meinen Anwendungsfall (Uhr als Rechner, Brille als Anzeige mit Mauszeiger)? Bitte jeden Punkt mit Aufwand und Machbarkeit bewerten, z. B.:
   - direkter Zugriff auf den Bildspeicher, frei gezeichneter Mauszeiger/Sprites, eigenes Zeichenprotokoll statt Container
   - höhere Update-Rate und geringere Latenz (eigenes, schlankeres BLE-Protokoll, größere Pakete, anderes Verbindungsintervall)
   - keine Container-Grenzen, eigene Schriften und Symbole, echte Graustufen-Grafik
   - Verhalten ohne Even-App: Energiesparen, Weckgesten, Sensoren (Touchflächen, IMU/Kopfbewegung, Mikrofon), Akku-Management
   - Abhängigkeit vom Hersteller: keine Überraschungen durch Firmware-Updates
   Und was man dafür **verliert**: offizielle Funktionen (Even-App, Updates, KI, Übersetzung, Benachrichtigungen), Garantie, ggf. Zulassung.
8. **Rechtliches und Garantie**: Garantieverlust, Funkzulassung (BLE-Parameter dürfen nicht verändert werden), Lizenz der Original-Firmware, Nutzungsbedingungen von Even Realities.

## Gewünschtes Ergebnis

Ein Dokument `RECHERCHE_FIRMWARE.md` mit:
- eine Gegenüberstellung Vorteile / Nachteile / Aufwand (Frage 7) als Tabelle
- Antworten auf die Fragen oben, jede Aussage mit Quelle und Sicherheit (belegt / wahrscheinlich / Vermutung)
- einer Risikobewertung (Wahrscheinlichkeit und Folgen eines Fehlschlags, Wiederherstellbarkeit)
- einer klaren Empfehlung: eigene Firmware ja/nein/später, und falls ja, ein Stufenplan, der mit ungefährlichen Schritten beginnt (nur lesen/analysieren, Emulation, dann erst Hardware)
- einer Liste der ungenutzten Möglichkeiten der Standard-Firmware aus Frage 6, die ich zuerst in meiner Uhr-App ausprobieren könnte

Arbeite gründlich, kennzeichne Unsicherheiten ehrlich und frage nach, wenn eine Entscheidung bei mir liegt.
