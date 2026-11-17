@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
rem ============================================================
rem  FileXplorer ワンコマンド リリースビルド (Windows用)
rem
rem    build.bat
rem
rem  テスト → APK ビルド → 署名 までを一括で行い、最後に成果物を
rem  表示します。
rem
rem    build.bat           テスト → ビルド → デバッグ署名
rem                        → dist\filexplorer.apk
rem    build.bat release   テスト → ビルド → リリース署名
rem                        → dist\filexplorer-release.apk
rem
rem  リリース署名には、実行前に環境変数で自分のキーストアを
rem  指定してください (スクリプト内に秘密情報は書きません):
rem
rem    set RELEASE_KEYSTORE=C:\path\to\release.jks
rem    set RELEASE_KEY_ALIAS=mykey
rem    set RELEASE_KEYSTORE_PASS=xxxx
rem    build.bat release
rem
rem  前提: JDK 17+ / Kotlin コンパイラ 2.0.21 / Android SDK
rem        (platform 34, build-tools 34.0.0)。
rem        SDK は ANDROID_SDK_ROOT か ANDROID_HOME、無ければ
rem        %LOCALAPPDATA%\Android\Sdk を使います。
rem        Kotlin は KOTLINC 環境変数、KOTLIN_HOME、PATH、
rem        %USERPROFILE%\tools\kotlin の順に探します。
rem ============================================================
cd /d "%~dp0"
set "MODE=%~1"
if not defined MODE set "MODE=debug"
if /i not "%MODE%"=="debug" if /i not "%MODE%"=="release" (
  echo 使い方: build.bat [debug^|release]
  goto :fail
)

rem ----- Java -----
if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
  set "JAR=%JAVA_HOME%\bin\jar.exe"
) else (
  set "JAVA=java"
  set "JAVAC=javac"
  set "KEYTOOL=keytool"
  set "JAR=jar"
)
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
  echo [エラー] Java が見つかりません。JDK 21 をインストールし、JAVA_HOME を設定してください。
  goto :fail
)

rem ----- Kotlin -----
if not defined KOTLINC if defined KOTLIN_HOME set "KOTLINC=%KOTLIN_HOME%\bin\kotlinc.bat"
if not defined KOTLINC if exist "%USERPROFILE%\tools\kotlin\bin\kotlinc.bat" set "KOTLINC=%USERPROFILE%\tools\kotlin\bin\kotlinc.bat"
if not defined KOTLINC (
  for /f "delims=" %%i in ('where kotlinc 2^>nul') do if not defined KOTLINC set "KOTLINC=%%i"
)
if not defined KOTLINC (
  echo [エラー] Kotlin コンパイラが見つかりません。
  echo Kotlin 2.0.21 をダウンロードして展開し、KOTLIN_HOME か KOTLINC 環境変数を設定してください。
  echo   https://github.com/JetBrains/kotlin/releases/tag/v2.0.21
  goto :fail
)
for %%i in ("%KOTLINC%") do set "KOTLIN_DIR=%%~dpi"
set "STDLIB=%KOTLIN_DIR%..\lib\kotlin-stdlib.jar"
if not exist "%STDLIB%" (
  echo [エラー] kotlin-stdlib.jar が見つかりません: %STDLIB%
  goto :fail
)

rem ----- Android SDK -----
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
set "BT=%ANDROID_SDK_ROOT%\build-tools\34.0.0"
set "AJAR=%ANDROID_SDK_ROOT%\platforms\android-34\android.jar"
if not exist "%AJAR%" (
  echo [エラー] Android SDK ^(platform 34^) が見つかりません: %ANDROID_SDK_ROOT%
  echo ANDROID_SDK_ROOT に SDK のパスを指定してください ^(build-tools 34.0.0 も必要です^)。
  goto :fail
)
echo Java   : %JAVA%
echo Kotlin : %KOTLINC%
echo SDK    : %ANDROID_SDK_ROOT%
echo.

set "BUILD=build\manual"
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\gen" "%BUILD%\classes" "%BUILD%\dex" "%BUILD%\test" 2>nul

echo === [1/2] 単体テスト ===
call "%KOTLINC%" -nowarn -include-runtime -d "%BUILD%\test\fileops-test.jar" app\src\main\java\jp\filexplorer\app\FileOps.kt tests\FileOpsTest.kt
if errorlevel 1 ( echo [エラー] テストのコンパイルに失敗しました。& goto :fail )
"%JAVA%" -cp "%BUILD%\test\fileops-test.jar" jp.filexplorer.app.FileOpsTestKt
if errorlevel 1 ( echo [エラー] 単体テストが失敗しました。& goto :fail )
echo.

echo === [2/2] APK ビルド ===
echo [1/6] aapt2 compile
"%BT%\aapt2.exe" compile --dir app\src\main\res -o "%BUILD%\compiled.zip"
if errorlevel 1 goto :fail
echo [2/6] aapt2 link
powershell -NoProfile -Command "$t=[IO.File]::ReadAllText('app\src\main\AndroidManifest.xml'); $q=[char]34; $old='<manifest xmlns:android='+$q+'http://schemas.android.com/apk/res/android'+$q; $new=$old+' package='+$q+'jp.filexplorer.app'+$q; $t=$t.Replace($old,$new); [IO.File]::WriteAllText('%BUILD%\AndroidManifest.xml', $t, (New-Object Text.UTF8Encoding $false))"
if errorlevel 1 goto :fail
"%BT%\aapt2.exe" link -o "%BUILD%\app-unsigned.apk" -I "%AJAR%" --manifest "%BUILD%\AndroidManifest.xml" "%BUILD%\compiled.zip" --java "%BUILD%\gen" --min-sdk-version 26 --target-sdk-version 34
if errorlevel 1 goto :fail
echo [3/6] kotlinc ^(+ javac for generated R.java^)
call "%KOTLINC%" -nowarn -jvm-target 17 -cp "%AJAR%" -d "%BUILD%\classes" app\src\main\java
if errorlevel 1 goto :fail
for /r "%BUILD%\gen" %%f in (R.java) do (
  "%JAVAC%" --release 17 -encoding UTF-8 -cp "%AJAR%" -d "%BUILD%\classes" "%%f"
  if errorlevel 1 goto :fail
)
echo [4/6] d8 (アプリ本体 + kotlin-stdlib)
call "%BT%\d8.bat" --min-api 26 --output "%BUILD%\dex" "%BUILD%\classes" "%STDLIB%"
if errorlevel 1 goto :fail
echo [5/6] package + zipalign
copy /y "%BUILD%\app-unsigned.apk" "%BUILD%\app-with-dex.apk" >nul
"%JAR%" --update --file "%BUILD%\app-with-dex.apk" -C "%BUILD%\dex" classes.dex
if errorlevel 1 goto :fail
"%BT%\zipalign.exe" -f -p 4 "%BUILD%\app-with-dex.apk" "%BUILD%\app-aligned.apk"
if errorlevel 1 goto :fail
echo [6/6] sign (%MODE%)
if /i "%MODE%"=="release" (
  if not defined RELEASE_KEYSTORE (
    echo [エラー] リリース署名には環境変数 RELEASE_KEYSTORE ^(キーストアのパス^) が必要です。
    goto :fail
  )
  if not exist "%RELEASE_KEYSTORE%" (
    echo [エラー] キーストアが見つかりません: %RELEASE_KEYSTORE%
    goto :fail
  )
  if not defined RELEASE_KEYSTORE_PASS (
    echo [エラー] 環境変数 RELEASE_KEYSTORE_PASS ^(キーストアのパスワード^) が必要です。
    goto :fail
  )
  set "KS=%RELEASE_KEYSTORE%"
  set "KS_PASS=%RELEASE_KEYSTORE_PASS%"
  if defined RELEASE_KEY_PASS ( set "K_PASS=%RELEASE_KEY_PASS%" ) else ( set "K_PASS=%RELEASE_KEYSTORE_PASS%" )
  set "ALIAS_OPT="
  if defined RELEASE_KEY_ALIAS set "ALIAS_OPT=--ks-key-alias %RELEASE_KEY_ALIAS%"
  set "OUT=dist\filexplorer-release.apk"
) else (
  set "KS=debug.keystore"
  set "KS_PASS=android"
  set "K_PASS=android"
  set "ALIAS_OPT=--ks-key-alias androiddebugkey"
  if not exist "debug.keystore" (
    "%KEYTOOL%" -genkeypair -keystore "debug.keystore" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=FileXplorer,C=JP" >nul 2>&1
  )
  set "OUT=dist\filexplorer.apk"
)
echo 署名キーストア: %KS%
call "%BT%\apksigner.bat" sign --ks "%KS%" --ks-pass pass:%KS_PASS% --key-pass pass:%K_PASS% %ALIAS_OPT% --out "%BUILD%\filexplorer.apk" "%BUILD%\app-aligned.apk"
if errorlevel 1 goto :fail
if not exist dist mkdir dist
copy /y "%BUILD%\filexplorer.apk" "%OUT%" >nul

echo.
echo ==================================================
echo ビルド完了 ^(%MODE%^)。成果物:
echo   %OUT%
for %%f in (%OUT%) do echo   サイズ: %%~zf bytes
if /i "%MODE%"=="debug" (
  echo   ※ デバッグ鍵で署名しています。配布用は RELEASE_KEYSTORE 等の
  echo     環境変数を設定して build.bat release を実行してください。
)
echo ==================================================
pause
endlocal
exit /b 0

:fail
echo ビルドを中止しました。
pause
endlocal
exit /b 1
