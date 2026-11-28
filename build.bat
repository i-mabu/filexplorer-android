@echo off
rem ============================================================
rem FileXplorer ワンコマンドビルド (Windows)
rem
rem   build.bat           テスト → ビルド → デバッグ署名
rem                       → dist\filexplorer.apk
rem   build.bat release   テスト → ビルド → リリース署名
rem                       → dist\filexplorer-release.apk
rem
rem リリース署名には自分のキーストアを環境変数で指定します
rem (スクリプト内に秘密情報は書きません):
rem
rem   set RELEASE_KEYSTORE=C:\path\to\release.jks
rem   set RELEASE_KEY_ALIAS=mykey
rem   set RELEASE_KEYSTORE_PASS=xxxx
rem   build.bat release
rem   (鍵パスワードが異なる場合だけ RELEASE_KEY_PASS も指定)
rem
rem 前提: JDK 17+ / Kotlin コンパイラ 2.0.21 / Android SDK
rem       (platform 34、build-tools 34.0.0) / python3 / curl。
rem       SDK の場所は ANDROID_SDK_ROOT か ANDROID_HOME で指定。
rem       Kotlin は KOTLINC / KOTLIN_HOME / PATH の順に探す。
rem       パッケージングには JDK 付属の jar を使う (外部 zip 不要)。
rem ============================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"
chcp 65001 >nul

set "MODE=%~1"
if not defined MODE set "MODE=debug"
if /i not "%MODE%"=="debug" if /i not "%MODE%"=="release" (
  echo 使い方: %~nx0 [debug^|release] 1>&2
  exit /b 1
)

rem --- リリース署名の前提チェック (ビルド前) -------------------
if /i "%MODE%"=="release" (
  if not defined RELEASE_KEYSTORE (
    echo [エラー] リリース署名には環境変数 RELEASE_KEYSTORE (キーストアのパス) が必要です。 1>&2
    exit /b 1
  )
  if not exist "%RELEASE_KEYSTORE%" (
    echo [エラー] キーストアが見つかりません: %RELEASE_KEYSTORE% 1>&2
    exit /b 1
  )
  if not defined RELEASE_KEYSTORE_PASS (
    echo [エラー] 環境変数 RELEASE_KEYSTORE_PASS (キーストアのパスワード) が必要です。 1>&2
    exit /b 1
  )
)

rem --- ツールチェーンの解決 -----------------------------------
if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\workspace\tools\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAR=%JAVA_HOME%\bin\jar.exe"
if not exist "%JAR%" set "JAR=jar"
if defined ANDROID_SDK_ROOT ( set "SDK=%ANDROID_SDK_ROOT%" ) else if defined ANDROID_HOME ( set "SDK=%ANDROID_HOME%" ) else set "SDK=%USERPROFILE%\workspace\tools\android\sdk"
set "BT=%SDK%\build-tools\34.0.0"
set "AJAR=%SDK%\platforms\android-34\android.jar"
if not exist "%AJAR%" ( echo android.jar が見つかりません: %AJAR% 1>&2 & exit /b 1 )
if not exist "%BT%\aapt2.exe" ( echo aapt2 が見つかりません: %BT% 1>&2 & exit /b 1 )

if not defined KOTLINC if defined KOTLIN_HOME set "KOTLINC=%KOTLIN_HOME%\bin\kotlinc.bat"
if not defined KOTLINC if exist "%USERPROFILE%\workspace\tools\kotlin\bin\kotlinc.bat" set "KOTLINC=%USERPROFILE%\workspace\tools\kotlin\bin\kotlinc.bat"
if not defined KOTLINC for /f "delims=" %%K in ('where kotlinc 2^>nul') do if not defined KOTLINC set "KOTLINC=%%K"
if not defined KOTLINC ( echo kotlinc が見つかりません。 1>&2 & exit /b 1 )
for %%D in ("%KOTLINC%\..") do set "KOTLIN_BIN=%%~fD"
for %%D in ("%KOTLIN_BIN%\..") do set "KOTLIN_HOME_DIR=%%~fD"
set "STDLIB=%KOTLIN_HOME_DIR%\lib\kotlin-stdlib.jar"
if not exist "%STDLIB%" ( echo kotlin-stdlib.jar が見つかりません: %STDLIB% 1>&2 & exit /b 1 )

rem --- [1/9] 単体テスト ----------------------------------------
echo [1/9] unit tests
if not exist "build\test-classes" mkdir "build\test-classes"
call "%KOTLINC%" -nowarn -d "build\test-classes" "app\src\main\java\jp\filexplorer\app\FileOps.kt" "tests\FileOpsTest.kt"
if errorlevel 1 exit /b 1
java -cp "build\test-classes;%STDLIB%" FileOpsTestKt
if errorlevel 1 exit /b 1

rem --- [2/9] 依存とツール jar の準備 ---------------------------
echo [2/9] dependencies
set "COMPOSE_PLUGIN=tools\kotlin-compose-compiler-plugin-2.0.21.jar"
set "R8_JAR=tools\r8-8.7.18.jar"
if not exist "%COMPOSE_PLUGIN%" (
  echo   downloading kotlin-compose-compiler-plugin 2.0.21
  curl -sfL -o "%COMPOSE_PLUGIN%" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21/kotlin-compose-compiler-plugin-2.0.21.jar"
  if errorlevel 1 ( echo Compose プラグインの取得に失敗しました。 1>&2 & exit /b 1 )
)
if not exist "%R8_JAR%" (
  echo   downloading r8 8.7.18
  curl -sfL -o "%R8_JAR%" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"
  if errorlevel 1 ( echo R8 の取得に失敗しました。 1>&2 & exit /b 1 )
)
if not exist "libs\artifacts.txt" (
  echo   resolving libraries (tools\resolve.py)
  python3 tools\resolve.py
  if errorlevel 1 exit /b 1
)

set "BUILD=build\manual"
set "AARS=build\aars"
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\gen" "%BUILD%\classes" "%BUILD%\dex" "%BUILD%\libres" "%AARS%" 2>nul

rem ライブラリ jar の収集 (artifacts.txt の順序どおり。
rem AAR の展開には JDK の jar を使う)
set "CP="
set "DEXJARS="
set "AARDIRS="
for /f "usebackq delims=" %%F in ("libs\artifacts.txt") do (
  set "ART=%%F"
  if /i "!ART:~0,13!"=="kotlin-stdlib" (
    rem stdlib は kotlinc 配布物を使うので読み飛ばす
  ) else if /i "!ART:~-4!"==".aar" (
    set "DIR=%AARS%\!ART:~0,-4!"
    set "AARDIRS=!AARDIRS! !DIR!"
    if not exist "!DIR!" (
      mkdir "!DIR!"
      pushd "!DIR!"
      "%JAR%" --extract --file "..\..\..\libs\!ART!"
      popd
    )
    if exist "!DIR!\classes.jar" (
      set "CP=!CP!;!DIR!\classes.jar"
      set "DEXJARS=!DEXJARS! "!DIR!\classes.jar""
    )
  ) else (
    rem クラスを含まない KMP ルート jar は除外する (重複クラス回避)
    set "HASCLASS=0"
    for /f %%N in ('"%JAR%" --list --file "libs\!ART!" 2^>nul ^| find /c ".class"') do set "HASCLASS=%%N"
    if not "!HASCLASS!"=="0" (
      set "CP=!CP!;libs\!ART!"
      set "DEXJARS=!DEXJARS! "libs\!ART!""
    )
  )
)
if defined CP set "CP=%CP:~1%"

rem --- [3/9] aapt2 compile (アプリ + ライブラリのリソース) -----
echo [3/9] aapt2 compile
"%BT%\aapt2.exe" compile --dir app\src\main\res -o "%BUILD%\libres\app.zip"
if errorlevel 1 exit /b 1
set "RESZIPS=%BUILD%\libres\app.zip"
set "EXTRAPKGS="
for %%D in (%AARDIRS%) do (
  if exist "%%D\res" (
    "%BT%\aapt2.exe" compile --dir "%%D\res" -o "%BUILD%\libres\%%~nD.zip"
    if errorlevel 1 exit /b 1
    set "RESZIPS=!RESZIPS! %BUILD%\libres\%%~nD.zip"
  )
  if exist "%%D\R.txt" (
    for /f "tokens=2 delims==" %%P in ('findstr /c:"package=" "%%D\AndroidManifest.xml"') do (
      set "PKG=%%P"
      set "PKG=!PKG:"=!"
      set "PKG=!PKG: =!"
      if defined EXTRAPKGS ( set "EXTRAPKGS=!EXTRAPKGS!:!PKG!" ) else set "EXTRAPKGS=!PKG!"
    )
  )
)

rem --- [4/9] aapt2 link ----------------------------------------
echo [4/9] aapt2 link
powershell -NoProfile -Command "(Get-Content -Raw 'app\src\main\AndroidManifest.xml') -replace '<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"', '<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"jp.filexplorer.app\"' | Set-Content -Encoding UTF8 '%BUILD%\AndroidManifest.xml'"
if errorlevel 1 exit /b 1
set "EXTRAARG="
if defined EXTRAPKGS set "EXTRAARG=--extra-packages %EXTRAPKGS%"
"%BT%\aapt2.exe" link -o "%BUILD%\app-unsigned.apk" -I "%AJAR%" --manifest "%BUILD%\AndroidManifest.xml" %RESZIPS% --java "%BUILD%\gen" --auto-add-overlay %EXTRAARG% --min-sdk-version 26 --target-sdk-version 34
if errorlevel 1 exit /b 1

rem --- [5/9] kotlinc (Compose コンパイラプラグイン有効) --------
echo [5/9] kotlinc
set "SOURCES="
for /r "app\src\main\java" %%S in (*.kt) do set "SOURCES=!SOURCES! "%%S""
for /r "%BUILD%\gen" %%S in (*.java) do set "SOURCES=!SOURCES! "%%S""
call "%KOTLINC%" -nowarn -jvm-target 17 -cp "%AJAR%;%CP%" -d "%BUILD%\classes" -Xplugin="%COMPOSE_PLUGIN%" %SOURCES%
if errorlevel 1 exit /b 1

rem --- [6/9] javac (生成された R クラス群) ---------------------
echo [6/9] javac (generated R classes)
set "GENJAVA="
for /r "%BUILD%\gen" %%S in (*.java) do set "GENJAVA=!GENJAVA! "%%S""
"%JAVA_HOME%\bin\javac.exe" --release 17 -encoding UTF-8 -cp "%AJAR%" -d "%BUILD%\classes" %GENJAVA%
if errorlevel 1 exit /b 1

rem --- [7/9] D8 (単体 R8 / マルチ dex) -------------------------
echo [7/9] d8 (standalone R8)
set "CLASSES="
for /r "%BUILD%\classes" %%C in (*.class) do set "CLASSES=!CLASSES! "%%C""
if not defined R8_JAVA_OPTS set "R8_JAVA_OPTS=-Xmx2g"
java %R8_JAVA_OPTS% -cp "%R8_JAR%" com.android.tools.r8.D8 --min-api 26 --lib "%AJAR%" --output "%BUILD%\dex" %CLASSES% %DEXJARS% "%STDLIB%"
if errorlevel 1 exit /b 1
dir /b "%BUILD%\dex"

rem --- [8/9] パッケージ + zipalign -----------------------------
echo [8/9] package + zipalign
copy /y "%BUILD%\app-unsigned.apk" "%BUILD%\app-with-dex.apk" >nul
"%JAR%" --update --file "%BUILD%\app-with-dex.apk" -C "%BUILD%\dex" .
rem AAR 内ネイティブライブラリ → lib\<abi>\ (jar で格納するため圧縮される。
rem インストール時に展開される方式になり、動作上の差はない)
for %%D in (%AARDIRS%) do (
  if exist "%%D\jni" (
    if not exist "%BUILD%\nativelib" mkdir "%BUILD%\nativelib"
    xcopy /e /i /y "%%D\jni" "%BUILD%\nativelib\lib" >nul
  )
)
if exist "%BUILD%\nativelib" (
  "%JAR%" --update --file "%BUILD%\app-with-dex.apk" -C "%BUILD%\nativelib" lib
)
"%BT%\zipalign.exe" -f -p 4 "%BUILD%\app-with-dex.apk" "%BUILD%\app-aligned.apk"
if errorlevel 1 exit /b 1

rem --- [9/9] 署名 ----------------------------------------------
echo [9/9] sign (%MODE%)
set "ALIAS_OPT="
if /i "%MODE%"=="release" (
  set "KS=%RELEASE_KEYSTORE%"
  set "KS_PASS=%RELEASE_KEYSTORE_PASS%"
  if defined RELEASE_KEY_PASS ( set "K_PASS=%RELEASE_KEY_PASS%" ) else set "K_PASS=%RELEASE_KEYSTORE_PASS%"
  if defined RELEASE_KEY_ALIAS set "ALIAS_OPT=--ks-key-alias %RELEASE_KEY_ALIAS%"
  set "OUT=dist\filexplorer-release.apk"
) else (
  set "KS=debug.keystore"
  set "KS_PASS=android"
  set "K_PASS=android"
  set "ALIAS_OPT=--ks-key-alias androiddebugkey"
  if not exist "debug.keystore" (
    "%JAVA_HOME%\bin\keytool.exe" -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=FileXplorer,C=JP" >nul 2>&1
  )
  set "OUT=dist\filexplorer.apk"
)
echo 署名キーストア: %KS%
"%BT%\apksigner.bat" sign --ks "%KS%" --ks-pass pass:"%KS_PASS%" --key-pass pass:"%K_PASS%" %ALIAS_OPT% --out "%BUILD%\filexplorer.apk" "%BUILD%\app-aligned.apk"
if errorlevel 1 exit /b 1
"%BT%\apksigner.bat" verify --print-certs "%BUILD%\filexplorer.apk"
if not exist dist mkdir dist
copy /y "%BUILD%\filexplorer.apk" "%OUT%" >nul

echo.
echo ==================================================
echo ビルド完了 (%MODE%)。成果物:
echo   %OUT%
for %%F in ("%OUT%") do echo   サイズ: %%~zF bytes
if /i "%MODE%"=="debug" (
  echo   ※ デバッグ鍵で署名しています。配布用は RELEASE_KEYSTORE 等の
  echo     環境変数を設定して build.bat release を実行してください。
)
echo ==================================================
exit /b 0
