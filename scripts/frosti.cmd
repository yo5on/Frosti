@echo off
rem Type "frosti" anywhere: (re)starts Core and the hotspot through the FROSTI wireless task.
schtasks /end /tn "FROSTI wireless" >nul 2>&1
rem an old Core would keep port 8765 and the new one would fail to start
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='python.exe'\" | Where-Object CommandLine -match 'frosti_core' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
schtasks /run /tn "FROSTI wireless" >nul || (echo Could not start the FROSTI wireless task. & exit /b 1)
echo FROSTI is starting. Console: http://127.0.0.1:8765/
