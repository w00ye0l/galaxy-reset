package com.forholiday.galaxyreset;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResetConfirmationTest {
    @Test public void requiresBothStaffAcknowledgementAndExactPhrase() {
        for (String phrase : new String[]{null,"","초기","동의"," 초기화","초기화 ","초기화\n"}) {
            assertFalse(ResetConfirmation.allowed(false,phrase));
            assertFalse(ResetConfirmation.allowed(true,phrase));
        }
        assertFalse(ResetConfirmation.allowed(false,"초기화"));
        assertTrue(ResetConfirmation.allowed(true,"초기화"));
    }
}
