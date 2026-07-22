FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/classes classes
COPY target/lib lib
# Exploded classpath, NOT the fat jar: the Surgeon spawns its MCP server with
# `java -cp <java.class.path>`, which only works when the classpath is real
# directories and jars — nested BOOT-INF entries are not spawnable.
ENTRYPOINT ["java", "-cp", "classes:lib/*", "io.github.hhagenbuch.medic.MedicApplication"]
