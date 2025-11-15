@ECHO OFF
setlocal

set SCRIPT_DIR=%~dp0
if "%SCRIPT_DIR:~-1%"=="\" set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%
set MAVEN_PROJECTBASEDIR=%SCRIPT_DIR%

set WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar
set WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties

REM Determine Java executable
if not defined JAVA_HOME (
  set JAVA_EXE=java.exe
) else (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
)

REM Download wrapper jar if missing
if not exist "%WRAPPER_JAR%" (
  echo Downloading Maven Wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $p=Get-Content '%WRAPPER_PROPERTIES%' | Where-Object { $_ -like 'wrapperUrl=*' }; $u=$p -replace 'wrapperUrl=',''; Invoke-WebRequest -Uri $u -OutFile '%WRAPPER_JAR%'"
)

REM Run Maven Wrapper
"%JAVA_EXE%" -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*

endlocal
