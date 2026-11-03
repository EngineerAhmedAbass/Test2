@echo off
chcp 65001 >nul
cd /d "%~dp0"
where node >nul 2>nul || (echo لازم تسطب Node.js الاول من https://nodejs.org & pause & exit /b)
start "" http://localhost:3000
node server.js
pause
