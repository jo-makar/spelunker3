default:
    @just --list

test *extra:
    @# --info --rerun and --test <specifier> are useful extra arguments
    ./gradlew test {{extra}}

build:
    @# Output: build/libs/spelunker3-<version>-all.jar
    ./gradlew shadowJar

default_jar := 'build/libs/spelunker3-1.0.0-SNAPSHOT-all.jar'
run jar=default_jar class='com.github.jo_makar.MainBrowserDemoKt' build='true' *args:
    #!/bin/bash
    set -o errexit

    if [ "{{build}}" = true ]; then
        if [ "{{jar}}" != "{{default_jar}}" ]; then
            echo cannot build non-default jar: {{jar}}
            exit 1
        fi
        just build
    fi

    java -Djava.util.logging.config.file=src/main/resources/logging.properties \
        -cp {{jar}} {{class}} \
        {{args}}

run-secgov *args:
    just run {{default_jar}} com.github.jo_makar.MainSecGovScraperKt true {{args}}