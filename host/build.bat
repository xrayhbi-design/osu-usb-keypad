@echo off
rem Builds OsuKeypadHost.exe with the C# compiler that is already part of Windows (.NET Framework 4.x).
rem No SDK or Visual Studio needed.
setlocal
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" (
  echo csc.exe not found. Enable the ".NET Framework 4.8 Advanced Services" Windows feature or install the .NET SDK.
  exit /b 1
)
"%CSC%" /nologo /target:winexe /optimize+ /out:"%~dp0OsuKeypadHost.exe" /r:System.Windows.Forms.dll /r:System.Drawing.dll "%~dp0OsuKeypadHost.cs"
if errorlevel 1 exit /b 1
echo Built OsuKeypadHost.exe
