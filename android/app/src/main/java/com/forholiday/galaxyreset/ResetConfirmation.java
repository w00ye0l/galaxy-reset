package com.forholiday.galaxyreset;

/** Deliberate acknowledgement for every full run, retry and resume; never persisted. */
final class ResetConfirmation {
    static boolean allowed(boolean staffConfirmed, String phrase) {
        return staffConfirmed && "초기화".equals(phrase);
    }
}
