@rem
@rem Copyright 2015 the original author or authors.
@rem Gradle startup script for Windows
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem Sanity checks
@rem ##########################################################################

if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Check wrapper jar - download if missing
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" goto downloadWrapper
for /f %%A in ('dir /b "%WRAPPER_JAR%" ^| find /c /v ""') do set JAR_EXISTS=%%A
goto checkJava

:downloadWrapper
echo Downloading gradle-wrapper.jar...
powershell -Command "& {Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%'}" 2>nul
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Could not download gradle-wrapper.jar
    echo Please run: gradle wrapper --gradle-version 8.4
    exit /b 1
)

:checkJava
if not "%JAVA_HOME%"=="" goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%"=="0" goto execute

echo ERROR: JAVA_HOME is not set and no 'java' found in PATH.
exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo ERROR: JAVA_HOME is set to invalid directory: %JAVA_HOME%
exit /b 1

:execute
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

%JAVA_EXE% %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  "-Dorg.gradle.appname=%APP_BASE_NAME%" ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*

:end
if "%ERRORLEVEL%"=="0" goto mainEnd

exit /b %ERRORLEVEL%

:mainEnd
if "%OS%"=="Windows_NT" endlocal
