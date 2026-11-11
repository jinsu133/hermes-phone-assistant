package com.mist.connect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Strict bounded FLAT protocol JSON only. No dependencies or exception payload retention. */
final class SourceControllerJson {
    private static final Pattern TOKEN=Pattern.compile("\\G[ \\t\\r\\n]*(\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|[{}:,])");
    static Map<String,Object> parse(byte[] bytes) throws IOException {
        if(bytes.length>4096)throw bad();
        final String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw bad();}
        Matcher m=TOKEN.matcher(text);ArrayList<String> tokens=new ArrayList<>();int end=0;
        while(m.find()){tokens.add(m.group(1));end=m.end();}
        if(!text.substring(end).matches("[ \\t\\r\\n]*") || tokens.size()<2)throw bad();
        Iterator<String> it=tokens.iterator();if(!it.next().equals("{"))throw bad();
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            String t=it.next();
            if(t.equals("}")){if(it.hasNext())throw bad();return result;}
            while(true) {
                String key=unquote(t);if(result.containsKey(key) || !it.next().equals(":"))throw bad();
                t=it.next();Object value;
                if(t.startsWith("\""))value=unquote(t);
                else if(t.equals("true") || t.equals("false"))value=Boolean.valueOf(t);
                else if(t.matches("-?(0|[1-9][0-9]*)"))value=Long.valueOf(t);
                else value=Double.valueOf(t);
                result.put(key,value);t=it.next();
                if(t.equals("}"))break;
                if(!t.equals(","))throw bad();t=it.next();
            }
            if(it.hasNext())throw bad();return result;
        }catch(NoSuchElementException | NumberFormatException failure){throw bad();}
    }
    private static String unquote(String s) throws IOException {
        if(s.length()<2 || s.charAt(0)!='"' || s.charAt(s.length()-1)!='"')throw bad();
        StringBuilder b=new StringBuilder();
        for(int i=1;i<s.length()-1;i++) {
            char c=s.charAt(i);
            if(c=='\\') {
                c=s.charAt(++i);
                switch(c){case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                    case 'r':c='\r';break;case 't':c='\t';break;
                    case 'u':c=(char)Integer.parseInt(s.substring(i+1,i+5),16);i+=4;break;
                    default:break;}
            }
            b.append(c);
        }
        return b.toString();
    }
    static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(char c:s.toCharArray()) {
            if(c=='"' || c=='\\')b.append('\\').append(c);
            else if(c<32 || c>126)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    static void keys(Map<String,Object> o,String... keys) throws IOException {
        if(!o.keySet().equals(new HashSet<>(Arrays.asList(keys))))throw bad();
    }
    static String string(Map<String,Object> o,String k) throws IOException {
        Object v=o.get(k);if(!(v instanceof String))throw bad();return (String)v;
    }
    static long integer(Map<String,Object> o,String k) throws IOException {
        Object v=o.get(k);if(!(v instanceof Long))throw bad();return (Long)v;
    }
    static IOException bad(){return new SourceControllerTransport.Failure(SourceControllerTransport.FailureCode.SCHEMA);}
}
