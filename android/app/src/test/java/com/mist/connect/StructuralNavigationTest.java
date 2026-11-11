package com.mist.connect;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class StructuralNavigationTest {
 @Test public void submitIsRestrictedToNativeSearchFields() {
  assertTrue(ActionRiskPolicy.nativeSearch("submit_search","arbitrary.package:id/search_src_text","android.widget.EditText","",true,false,"서울 날씨"));
  // Unknown IDs may use native shape plus their own search label, not label alone.
  assertTrue(ActionRiskPolicy.nativeSearch("submit_search","other:id/message","android.widget.EditText","검색",true,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("submit_search","other:id/message","android.widget.EditText","메시지",true,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("submit_search","other:id/message","android.widget.TextView","검색",true,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("submit_search","other:id/message","android.widget.EditText","검색",false,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("submit_search","other:id/search_src_text","android.widget.EditText","",true,true,"hello"));
  for(String input:new String[]{"javascript:alert(1)","intent://call", "tel:123", "sms:123", "mailto:test@example.com", "one\ntwo"})
   assertFalse(ActionRiskPolicy.nativeSearch("submit_search","x:id/url_bar","android.widget.EditText","",true,false,input));
 }
 @Test public void messageRowsAreNotMessageSendButtons() {
  Set<String> children=Set.of("name","message","time");
  assertEquals("conversation_row",ActionRiskPolicy.nativeListRole("x:id/item","android.widget.Button",true,false,false,true,"recycler_view",children));
  assertEquals("conversation_row",ActionRiskPolicy.nativeListRole("y:id/container","android.widget.Button",true,false,false,true,"recycler_view",Set.of("lastMessage","time","unread_count")));
  assertEquals("conversation_row",ActionRiskPolicy.nativeListRole("y:id/container","android.widget.Button",true,false,false,true,"recycler_view",Set.of("lastMessage","time")));
  for(String id:new String[]{"send","delete","purchase"})
   assertEquals("",ActionRiskPolicy.nativeListRole("x:id/"+id,"android.widget.Button",true,false,false,true,"recycler_view",children));
  assertEquals("",ActionRiskPolicy.nativeListRole("x:id/item","android.widget.Button",true,true,false,true,"recycler_view",children));
  assertEquals("",ActionRiskPolicy.nativeListRole("x:id/item","android.widget.Button",true,false,true,true,"recycler_view",children));
  assertEquals("",ActionRiskPolicy.nativeListRole("x:id/item","android.widget.Button",true,false,false,false,"recycler_view",children));
  assertEquals("",ActionRiskPolicy.nativeListRole("x:id/item","android.widget.Button",true,false,false,true,"recycler_view",Set.of("name","price")));
 }
 @Test public void suggestionNeedsNativeListAndChildRoles() {
  assertEquals("search_suggestion",ActionRiskPolicy.nativeListRole("","android.view.ViewGroup",true,false,false,true,"omnibox_suggestions_dropdown",Set.of("line_1")));
  assertEquals("",ActionRiskPolicy.nativeListRole("","android.view.ViewGroup",true,false,false,true,"shopping_list",Set.of("line_1")));
  assertEquals("",ActionRiskPolicy.nativeListRole("","android.view.ViewGroup",true,false,true,true,"omnibox_suggestions_dropdown",Set.of("line_1")));
 }
}
