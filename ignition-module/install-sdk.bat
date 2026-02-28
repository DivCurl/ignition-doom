@echo off
REM Extract and install Ignition SDK from Docker container

echo ====================================
echo Installing Ignition SDK
echo ====================================

REM Get container name from user
set /p CONTAINER_NAME="Enter your Ignition Docker container name (or press Enter for 'ignition'): "
if "%CONTAINER_NAME%"=="" set CONTAINER_NAME=ignition

echo.
echo Using container: %CONTAINER_NAME%
echo.

REM Create SDK directory
if not exist sdk mkdir sdk

REM Extract JARs from container
echo [1/4] Extracting common.jar (ignition-common)...
docker cp %CONTAINER_NAME%:/usr/local/bin/ignition/lib/core/common/common.jar sdk/ignition-common.jar
if errorlevel 1 (
    echo ERROR: Failed to extract common.jar
    echo Make sure the container name is correct and container is running
    pause
    exit /b 1
)

echo [2/4] Extracting gateway-api-8.3.1.jar...
docker cp %CONTAINER_NAME%:/usr/local/bin/ignition/lib/core/gateway/gateway-api-8.3.1.jar sdk/gateway-api.jar
if errorlevel 1 (
    echo ERROR: Failed to extract gateway-api.jar
    pause
    exit /b 1
)

REM Install to Maven repository
echo [3/4] Installing ignition-common to Maven...
call mvn install:install-file -Dfile=sdk/ignition-common.jar ^
    -DgroupId=com.inductiveautomation.ignitionsdk ^
    -DartifactId=ignition-common ^
    -Dversion=8.3.1 ^
    -Dpackaging=jar

echo [4/4] Installing gateway-api to Maven...
call mvn install:install-file -Dfile=sdk/gateway-api.jar ^
    -DgroupId=com.inductiveautomation.ignitionsdk ^
    -DartifactId=gateway-api ^
    -Dversion=8.3.1 ^
    -Dpackaging=jar

echo.
echo ====================================
echo SDK Installation Complete!
echo ====================================
echo.
echo You can now run: build-module.bat
echo.
pause
