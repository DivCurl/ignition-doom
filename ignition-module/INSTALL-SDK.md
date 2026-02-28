# Installing Ignition SDK for Development

The Ignition SDK is available via Inductive Automation's public Maven repository, which is
already configured in `ignition-module/pom.xml`. Running `mvn clean install` should download
the SDK automatically.

If the public Nexus is unavailable (air-gapped environment, network issues), extract the JARs
directly from a running Ignition Docker container:

```bash
# Find your container name
docker ps

# Copy SDK JARs from container
docker cp ignition-container:/usr/local/bin/ignition/lib/core/common/common.jar ./sdk/ignition-common.jar
docker cp ignition-container:/usr/local/bin/ignition/lib/core/gateway/gateway-api-8.3.1.jar ./sdk/gateway-api.jar

# Install to local Maven repository
mvn install:install-file -Dfile=sdk/ignition-common.jar \
    -DgroupId=com.inductiveautomation.ignitionsdk \
    -DartifactId=ignition-common \
    -Dversion=8.3.1 \
    -Dpackaging=jar

mvn install:install-file -Dfile=sdk/gateway-api.jar \
    -DgroupId=com.inductiveautomation.ignitionsdk \
    -DartifactId=gateway-api \
    -Dversion=8.3.1 \
    -Dpackaging=jar
```

Or run `install-sdk.bat` which does the same steps interactively.

## Verification

```bash
ls ~/.m2/repository/com/inductiveautomation/ignitionsdk/ignition-common/8.3.1/
ls ~/.m2/repository/com/inductiveautomation/ignitionsdk/gateway-api/8.3.1/
```
