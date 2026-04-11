-- Migration: one admin per email (case-insensitive). Empty email is allowed for multiple admins.
-- Run once: python -m backend.run_schema (or run this file manually).
CREATE UNIQUE INDEX IF NOT EXISTS admins_email_unique
ON admins (LOWER(TRIM(email)))
WHERE TRIM(email) != '';
