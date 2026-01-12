default:
    @just --list

test *extra:
    @# --info --rerun and --test <specifier> are useful extra arguments
    ./gradlew test {{extra}}

version := "1.0.0-SNAPSHOT"

build:
    @# Output: build/libs/spelunker3-{{version}}-all.jar
    ./gradlew shadowJar

run-browser-demo:
    ./gradlew shadowJar
    java -jar build/libs/spelunker3-{{version}}-all.jar com.github.jo_makar.MainBrowserDemo
