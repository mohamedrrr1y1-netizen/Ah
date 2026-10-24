@echo off
setlocal
set VERSION=8.9
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_DIR=%GRADLE_USER_HOME%\hamo-distributions\gradle-%VERSION%
set GRADLE_BIN=%DIST_DIR%\bin\gradle.bat
if exist "%GRADLE_BIN%" goto runGradle
if not exist "%GRADLE_USER_HOME%\hamo-distributions" mkdir "%GRADLE_USER_HOME%\hamo-distributions"
echo Downloading the official Gradle %VERSION% distribution...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:GRADLE_USER_HOME 'hamo-distributions\gradle-%VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $env:GRADLE_USER_HOME 'hamo-distributions') -Force; Remove-Item $zip -Force; Move-Item (Join-Path $env:GRADLE_USER_HOME 'hamo-distributions\gradle-%VERSION%') '%DIST_DIR%' -Force"
if errorlevel 1 exit /b 1
:runGradle
call "%GRADLE_BIN%" %*
endlocal
