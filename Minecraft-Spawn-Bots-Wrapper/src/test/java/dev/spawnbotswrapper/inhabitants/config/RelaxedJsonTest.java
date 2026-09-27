package dev.spawnbotswrapper.inhabitants.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelaxedJsonTest {

    private static com.google.gson.JsonElement parse(String s) {
        return JsonParser.parseString(RelaxedJson.relax(s));
    }

    @Test
    void stripsTrailingCommasInObjectsAndArrays() {
        var o = parse("{ \"a\": [1, 2, 3, ], \"b\": { \"c\": 1, }, }").getAsJsonObject();
        assertEquals(3, o.getAsJsonArray("a").size());
        assertEquals(1, o.getAsJsonObject("b").get("c").getAsInt());
    }

    @Test
    void stripsLineAndBlockComments() {
        var o = parse("""
                {
                  // line comment
                  "a": 1, /* block
                  comment */ "b": 2
                }
                """).getAsJsonObject();
        assertEquals(1, o.get("a").getAsInt());
        assertEquals(2, o.get("b").getAsInt());
    }

    @Test
    void neverTouchesStringContents() {
        var o = parse("{ \"url\": \"http://x.y/z//not-a-comment\", \"t\": \"a,}\", \"q\": \"say \\\"hi\\\" /* no */\" }").getAsJsonObject();
        assertEquals("http://x.y/z//not-a-comment", o.get("url").getAsString());
        assertEquals("a,}", o.get("t").getAsString());
        assertEquals("say \"hi\" /* no */", o.get("q").getAsString());
    }

    @Test
    void validJsonPassesThroughUnchangedInMeaning() {
        String s = "{\"a\":[1,2,{\"b\":null}],\"c\":\"d\"}";
        assertEquals(JsonParser.parseString(s), parse(s));
    }

    @Test
    void unterminatedBlockCommentDoesNotHang() {
        assertDoesNotThrow(() -> RelaxedJson.relax("{ \"a\": 1 /* never closed"));
    }
}
