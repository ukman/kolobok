#!/bin/bash

set -euo pipefail

JAVA_HOME="/Users/ukman/.sdkman/candidates/java/21.0.6-tem"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mvn spring-boot:run
