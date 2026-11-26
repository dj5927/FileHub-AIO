package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupCrashNoteTest {
    @Test public void reportContainsClassesAndLinesButNeverExceptionMessagesOrFileNames(){
        Exception cause=new IllegalArgumentException("password=secret");
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("a.b","open","private-name.xlsx",42)});
        Exception failure=new IllegalStateException("https://example.invalid/private",cause);
        String note=StartupCrashNote.format(failure,"test");
        assertTrue(note.contains("java.lang.IllegalStateException"));
        assertTrue(note.contains("caused by: java.lang.IllegalArgumentException"));
        assertTrue(note.contains("a.b.open:42"));
        assertFalse(note.contains("secret"));
        assertFalse(note.contains("example.invalid"));
        assertFalse(note.contains("private-name.xlsx"));
    }
}
