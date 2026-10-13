#!/bin/sh
# Braucht JDK 25 und Maven. Ergebnis: target/ServerExtras.jar
mvn -q package && echo "Fertig: target/ServerExtras.jar"
