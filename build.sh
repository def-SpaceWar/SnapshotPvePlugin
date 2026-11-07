#!/bin/bash

rm -rf ./build/libs/*.jar
./gradlew build $@
mv ./build/libs/*.jar ~/Downloads/SnapshotPvePlugin.jar
