@echo off
setlocal
chcp 65001 >nul
echo.
echo [启动] 正在启动后端服务...
echo.

set "APP_DIR=%~dp0..\zzl-admin\target"
set "APP_JAR=zzl-admin.jar"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m"

where java >nul 2>nul
if errorlevel 1 (
    echo [失败] 未找到 Java，请安装 JDK 21 并配置 PATH。
    goto startup_failed
)
if not exist "%APP_DIR%\%APP_JAR%" (
    echo [失败] 未找到启动包 "%APP_DIR%\%APP_JAR%"。
    echo 请先运行 bin\package.bat 完成打包。
    goto startup_failed
)
pushd "%APP_DIR%"
if errorlevel 1 goto startup_failed

java %JAVA_OPTS% -jar "%APP_JAR%" %*
set "APP_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%APP_EXIT_CODE%"=="0" (
    echo.
    echo [失败] 后端启动或运行异常，退出码 %APP_EXIT_CODE%。请查看上方错误。
    echo 如果提示没有主清单属性，请停止旧服务后重新运行 bin\package.bat。
)
if not defined ZZL_RUN_NO_PAUSE pause
exit /b %APP_EXIT_CODE%

:startup_failed
if not defined ZZL_RUN_NO_PAUSE pause
exit /b 1
