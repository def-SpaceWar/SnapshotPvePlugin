@echo off
setlocal

pushd "%~dp0" || exit /b 1

if exist "build\libs\*.jar" del /q "build\libs\*.jar"
if exist "build\libs\*.jar" (
    echo Failed to remove existing JAR files from build\libs.
    set "RESULT=1"
    goto :finish
)

call gradlew.bat build %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" goto :finish

if not exist "%USERPROFILE%\Downloads" mkdir "%USERPROFILE%\Downloads"
if errorlevel 1 (
    echo Failed to create the Downloads directory.
    set "RESULT=1"
    goto :finish
)

set "FOUND_JAR="
for %%F in ("build\libs\*.jar") do if exist "%%~fF" (
    set "FOUND_JAR=1"
    copy /y "%%~fF" "%USERPROFILE%\Downloads\SnapshotPvePlugin.jar" >nul
    if errorlevel 1 (
        echo Failed to copy the built JAR to Downloads.
        set "RESULT=1"
        goto :finish
    )
)

if not defined FOUND_JAR (
    echo Build completed, but no JAR was found in build\libs.
    set "RESULT=1"
)

:finish
popd
exit /b %RESULT%
