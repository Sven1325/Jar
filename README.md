# ServerExtras (Paper 26.2 / 26.3, Java 25)
Eine Jar mit zwei Funktionen:
- Rezepte: unsichtbarer Item-Rahmen und Lichtquelle (je 8 Glasscheiben + Item-Rahmen bzw. Fackel in der Mitte)
- /mapimage <URL> [2x2] [fit|fill|stretch]  (Permission mapimage.use, Standard: OP)

Bauen: JDK 25 + Maven installieren, dann build.bat (Windows) oder ./build.sh ausfuehren.
Die fertige Datei target/ServerExtras.jar in den plugins-Ordner kopieren, Server neu starten.

## Automatisch bauen mit GitHub
1. Neues Repository auf github.com anlegen und den kompletten Inhalt dieses Ordners hochladen (inkl. .github).
2. Tab "Actions" oeffnen: der Build "Build ServerExtras" startet automatisch (oder "Run workflow").
3. Nach ca. 1-2 Minuten den fertigen Lauf anklicken und unter "Artifacts" die Datei ServerExtras herunterladen.
   Das ZIP enthaelt ServerExtras.jar. Diese in den plugins-Ordner kopieren.
