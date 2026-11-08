#!/bin/sh
# Voraussetzung: JDK 25 und Gradle 9.6.0 installiert (gradle im PATH)
gradle build && echo "Fertig! Die Mod liegt in: build/libs/clientveinminer-1.0.0.jar"
