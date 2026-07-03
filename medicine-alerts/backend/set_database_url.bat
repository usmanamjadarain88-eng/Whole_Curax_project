@echo off
REM Prefer backend/.env (copy from .env.example). This bat is optional for Windows CMD only.
REM Edit the line below: set your PostgreSQL password and keep other values if you use defaults.
REM Format: postgresql://USER:PASSWORD@HOST:PORT/DATABASE
REM Default: user=postgres, host=localhost, port=5432, database=curax_central
REM (PostgreSQL 18.2 installs to ...\PostgreSQL\18\...)
set DATABASE_URL=postgresql://postgres:YOUR_PASSWORD@localhost:5432/curax_central

REM To start server: run this, then: python -m backend.api_server
REM To test API (separate terminal, server must be running): python backend\test_api.py
echo DATABASE_URL is set.
