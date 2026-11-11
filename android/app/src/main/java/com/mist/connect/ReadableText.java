package com.mist.connect;

/** Bounded Unicode-code-point paging. Never divide a UTF-16 surrogate pair. */
public final class ReadableText {
    private ReadableText() {}
    public static String preview(String text,int limit) {
        if (limit<0) throw new IllegalArgumentException("unsupported_ui");
        int count=text.codePointCount(0,text.length());
        return text.substring(0,text.offsetByCodePoints(0,Math.min(limit,count)));
    }
    public static Page page(String text,int offset) {
        // Keep the original storage/memory limit even for supplementary characters.
        if (text.length()>64000 || offset<0) throw new IllegalArgumentException("unsupported_ui");
        int total=text.codePointCount(0,text.length());
        if (offset>total) throw new IllegalArgumentException("unsupported_ui");
        int end=Math.min(offset+2000,total);
        int beginIndex=text.offsetByCodePoints(0,offset);
        int endIndex=text.offsetByCodePoints(beginIndex,end-offset);
        return new Page(text.substring(beginIndex,endIndex),offset,end,total);
    }
    public static final class Page {
        public final String content;
        public final int offset,nextOffset,totalChars;
        private Page(String content,int offset,int nextOffset,int totalChars) {
            this.content=content; this.offset=offset; this.nextOffset=nextOffset; this.totalChars=totalChars;
        }
        public boolean hasMore() { return nextOffset<totalChars; }
    }
}
