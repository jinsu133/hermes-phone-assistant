package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class HostCodeExecutionTest {
    private static final String SOURCE="d287bb7f9d15abdc5b6e98536263815744b6ef21c8f3c839fc434ca70d8efe99";
    private static final String ACTION="689b6b3a7f9af6f051a6f0e6ceb33b712eca8dde48fd77318aa92a809fd9f393";
    @Test public void independentPythonDigestAndCompletePreview() {
        HostCodeExecution p=new HostCodeExecution("owner","test.py","print(1)",SOURCE,ACTION);
        assertEquals(ACTION,p.digest);assertTrue(p.prompt().contains("print(1)"));
        assertTrue(p.prompt().contains(HostCodeExecution.PHRASE));
        assertTrue(p.prompt().contains("파일 접근, 인터넷 연결"));
    }
    @Test public void sourceNameOwnerAndDigestCannotChange() {
        assertThrows(IllegalArgumentException.class,()->new HostCodeExecution("owner","test.py","print(2)",SOURCE,ACTION));
        assertThrows(IllegalArgumentException.class,()->new HostCodeExecution("other","test.py","print(1)",SOURCE,ACTION));
        assertThrows(IllegalArgumentException.class,()->new HostCodeExecution("owner","../test.py","print(1)",SOURCE,ACTION));
        assertThrows(IllegalArgumentException.class,()->new HostCodeExecution("owner","test.py","x".repeat(2001),SOURCE,ACTION));
    }
}
