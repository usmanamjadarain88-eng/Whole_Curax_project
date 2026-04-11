-- Migration: store desktop unlock password hash per admin so "recover by access code" restores same password.
ALTER TABLE admins ADD COLUMN IF NOT EXISTS desktop_password_hash VARCHAR(128);
