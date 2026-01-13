default:
    @just --list

test *extra:
    @# --info --rerun and --test <specifier> are useful extra arguments
    ./gradlew test {{extra}}

build:
    @# Output: build/libs/spelunker3-<version>-all.jar
    ./gradlew shadowJar

default_jar := 'build/libs/spelunker3-1.0.0-SNAPSHOT-all.jar'
run jar=default_jar class='com.github.jo_makar.MainBrowserDemo' build='true':
    #!/bin/bash
    set -o errexit

    if [ "{{build}}" = true ]; then
        if [ "{{jar}}" != "{{default_jar}}" ]; then
            echo cannot build non-default jar: {{jar}}
            exit 1
        fi
        just build
    fi

    java -jar {{jar}} {{class}} \
        -Dsimplelogger.properties=src/main/resources/simplelogger.properties