#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/tests
javac --release 8 -cp .cache/json-20250517.jar -d build/tests app/src/main/java/dev/navix/agent/Protocol.java app/src/main/java/dev/navix/agent/AmCommand.java app/src/main/java/dev/navix/agent/CommandRunner.java app/src/main/java/dev/navix/agent/TaskPolicy.java app/src/main/java/dev/navix/agent/WebSearch.java tests/WebSearchTest.java tests/ProtocolTest.java tests/AmToolsTest.java tests/TaskPolicyTest.java
java -cp build/tests:.cache/json-20250517.jar dev.navix.agent.ProtocolTest
java -cp build/tests:.cache/json-20250517.jar dev.navix.agent.AmToolsTest
java -cp build/tests:.cache/json-20250517.jar dev.navix.agent.TaskPolicyTest
java -cp build/tests:.cache/json-20250517.jar dev.navix.agent.WebSearchTest
