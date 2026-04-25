@echo off
setlocal
set MAVEN_PROJECTBASEDIR=%~dp0
set WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar
set WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip

if exist "%WRAPPER_JAR%" goto execute
echo Downloading Maven Wrapper JAR...
powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"

:execute
"%JAVA_HOME%\bin\java.exe" -jar "%WRAPPER_JAR%" %*