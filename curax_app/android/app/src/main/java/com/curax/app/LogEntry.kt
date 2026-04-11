package com.curax.app

/**
 * One row in the Logs table: Date, Time, Medicine, Status, Source, User (name/email for multi-user admin).
 */
data class LogEntry(
    val date: String,
    val time: String,
    val medicineName: String,
    val status: String,
    val source: String,
    val userName: String = ""
)
