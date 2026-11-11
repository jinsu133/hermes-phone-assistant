package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadableTextTest {
    @Test public void previewDoesNotSplitSupplementaryCharacters() {
        String text="가".repeat(179)+"😀"+"끝";
        assertEquals("가".repeat(179)+"😀",ReadableText.preview(text,180));
    }
    @Test public void pagesReassembleExactlyAcrossEmojiBoundary() {
        String text="가".repeat(1999)+"😀"+"끝👨‍👩‍👧‍👦";
        ReadableText.Page first=ReadableText.page(text,0);
        assertEquals("가".repeat(1999)+"😀",first.content);
        assertTrue(first.hasMore());
        ReadableText.Page second=ReadableText.page(text,first.nextOffset);
        assertEquals(text,first.content+second.content);
        assertFalse(second.hasMore());
        assertEquals(text.codePointCount(0,text.length()),second.totalChars);
    }
    @Test public void exactBoundaryAndEmptyPageAreTerminal() {
        ReadableText.Page one=ReadableText.page("나".repeat(2000),0);
        assertFalse(one.hasMore());
        assertEquals("",ReadableText.page("",0).content);
        assertEquals("",ReadableText.page("😀",1).content);
    }
    @Test public void invalidOffsetsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ReadableText.page("😀",-1));
        assertThrows(IllegalArgumentException.class,()->ReadableText.page("😀",2));
    }
    @Test public void sourceSizeRemainsBounded() {
        assertThrows(IllegalArgumentException.class,()->ReadableText.page("가".repeat(64001),0));
        assertThrows(IllegalArgumentException.class,()->ReadableText.page("😀".repeat(32001),0));
    }
    @Test public void previewPreservesShortTextAndCombiningMarks() {
        String text="한글 é";
        assertEquals(text,ReadableText.preview(text,180));
        assertEquals(text,ReadableText.page(text,0).content);
    }
}
